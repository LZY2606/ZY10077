package com.example.migsb.engine;

import com.example.migsb.domain.MigrationSpec;
import com.example.migsb.domain.States;
import com.example.migsb.store.ControlStore;
import com.example.migsb.store.Rows.Journal;
import com.example.migsb.store.Rows.Run;
import com.example.migsb.store.Rows.Step;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RecoveryScanner extends EngineSupport {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScanner.class);

    public RecoveryScanner(ControlStore store, SandboxService sandbox,
                           ObjectMapper mapper, CrashSimulator crash) {
        super(store, sandbox, mapper, crash);
    }

    public record ScanReport(List<RecoveryVerdict> verdicts, List<String> halted) {}

    /**
     * 重启后扫描所有未终局运行。只基于日志与探针判定，绝不凭步骤名或类型猜测重跑。
     */
    public ScanReport scanAll(boolean apply) {
        List<RecoveryVerdict> verdicts = new ArrayList<>();
        List<String> halted = new ArrayList<>();
        for (Run run : store.listRuns()) {
            if (List.of(States.RUN_COMMITTED, States.RUN_ROLLED_BACK,
                    States.RUN_CREATED).contains(run.status())) {
                continue;
            }
            RecoveryVerdict verdict = classifyRun(run);
            try {
                com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode();
                payload.put("status", verdict.status());
                payload.put("reason", verdict.reason());
                store.appendJournal(run.runUid(), verdict.stepId(), Events.RECOVERY_VERDICT,
                        mapper.writeValueAsString(payload));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            verdicts.add(verdict);
            switch (verdict.status()) {
                case RecoveryVerdict.UNCERTAIN -> {
                    if (!States.RUN_HALTED.equals(run.status())) {
                        store.updateRunStatus(run.runUid(), States.RUN_HALTED,
                                "重启恢复判定为 UNCERTAIN: " + verdict.reason());
                    }
                    if (verdict.stepId() != null) {
                        store.updateStepStatus(run.id(), verdict.stepId(), States.STEP_HALTED);
                    }
                    halted.add(run.runUid());
                    log.warn("运行 {} 在步骤 {} 处于无法证明安全的中间态: {}",
                            run.runUid(), verdict.stepId(), verdict.reason());
                }
                case RecoveryVerdict.FINALIZED, RecoveryVerdict.COMMITTED -> {
                    if (apply) {
                        // 终局事件已在日志中，仅修正控制面状态
                        if (RecoveryVerdict.FINALIZED.equals(verdict.status())) {
                            store.updateRunTerminalEvent(run.runUid(), Events.RUN_COMPLETED,
                                    States.RUN_COMMITTED);
                        }
                    }
                }
                default -> {
                    // NOT_STARTED / RESUMABLE / WINDOW_OPEN / ROLLBACK_RESUMABLE：保持 RUNNING，等待用户继续
                }
            }
        }
        return new ScanReport(verdicts, halted);
    }

    public RecoveryVerdict classifyRun(Run run) {
        MigrationSpec spec = specOf(run);
        List<Step> steps = steps(run);

        if (Events.RUN_ROLLED_BACK.equals(run.terminalEvent())) {
            return new RecoveryVerdict(run.runUid(), null, RecoveryVerdict.COMMITTED,
                    "日志已有回滚终局事件");
        }
        if (States.RUN_ROLLING_BACK.equals(run.status())) {
            return new RecoveryVerdict(run.runUid(), null, RecoveryVerdict.ROLLBACK_RESUMABLE,
                    "回滚过程中进程退出，可按 WAL 幂等继续逆序补偿");
        }

        Step firstInFlight = null;
        for (Step step : steps) {
            if (!States.STEP_COMMITTED.equals(step.status())) {
                firstInFlight = step;
                break;
            }
        }
        if (firstInFlight == null) {
            boolean completionSeen = store.listJournal(run.runUid()).stream()
                    .anyMatch(j -> Events.RUN_COMPLETED.equals(j.event()));
            if (completionSeen) {
                return new RecoveryVerdict(run.runUid(), null, RecoveryVerdict.FINALIZED,
                        "全部步骤已提交且日志存在 RUN_COMPLETED");
            }
            return new RecoveryVerdict(run.runUid(), null, RecoveryVerdict.RESUMABLE,
                    "全部步骤已提交但缺少终局事件，需重新执行完成判定（不变量+读路径）");
        }

        var def = spec.steps().get(firstInFlight.seqNo());
        List<Journal> stepJournal = store.listJournal(run.runUid()).stream()
                .filter(j -> def.id().equals(j.stepId())).toList();
        boolean started = stepJournal.stream().anyMatch(j -> Events.STEP_STARTED.equals(j.event()));
        if (!started) {
            return new RecoveryVerdict(run.runUid(), def.id(), RecoveryVerdict.NOT_STARTED,
                    "无 STEP_STARTED 记录，步骤从未开始，可直接执行");
        }
        return classifyStep(run, def, firstInFlight, stepJournal);
    }

    private RecoveryVerdict classifyStep(Run run, MigrationSpec.StepDef def, Step step,
                                         List<Journal> stepJournal) {
        boolean committed = stepJournal.stream().anyMatch(j -> Events.STEP_COMMITTED.equals(j.event()));
        if (committed) {
            store.updateStepStatus(run.id(), step.stepId(), States.STEP_COMMITTED);
            return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.COMMITTED,
                    "存在 STEP_COMMITTED，步骤已完整提交，重启时跳过");
        }
        switch (step.stepType()) {
            case States.TYPE_DDL -> {
                boolean applied = stepJournal.stream().anyMatch(j -> Events.DDL_APPLIED.equals(j.event()));
                if (!applied) {
                    return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.NOT_STARTED,
                            "DDL_APPLIED 未落盘，DDL 未生效或其提交随崩溃丢失");
                }
                if (States.PROBE_TABLE_EXISTS.equals(def.probeType()) && def.probeTable() != null) {
                    boolean exists = sandbox.tableExists(run.sandboxSchema(), def.probeTable());
                    if (exists) {
                        store.updateStepStatus(run.id(), step.stepId(), States.STEP_COMMITTED);
                        journal(run, step.stepId(), Events.STEP_SKIPPED_COMMITTED,
                                map("proof", "TABLE_EXISTS:" + def.probeTable()));
                        return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.COMMITTED,
                                "DDL_APPLIED 已落盘且探针证明对象存在，视为已提交");
                    }
                    return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.UNCERTAIN,
                            "DDL_APPLIED 已落盘但探针表不存在：可能是多语句 DDL 部分生效，无法证明安全");
                }
                return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.UNCERTAIN,
                        "DDL 在 AFTER_APPLY 崩溃且无可用探针，不能凭步骤名猜测，等待人工裁定");
            }
            case States.TYPE_SIDE_EFFECT -> {
                boolean applied = stepJournal.stream()
                        .anyMatch(j -> Events.SIDE_EFFECT_APPLIED.equals(j.event()));
                if (!applied) {
                    return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.UNCERTAIN,
                            "副作用在 AFTER_APPLY 崩溃：外部系统是否已收到通知无法由沙箱证明，等待人工裁定");
                }
                return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.UNCERTAIN,
                        "副作用存在应用记录但缺 STEP_COMMITTED，等待人工裁定");
            }
            case States.TYPE_BACKFILL -> {
                long batches = stepJournal.stream()
                        .filter(j -> Events.BACKFILL_BATCH_COMMITTED.equals(j.event())).count();
                if (batches == 0) {
                    long targetRows = sandbox.countRows(run.sandboxSchema(), def.target(), null);
                    if (targetRows == 0) {
                        return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.NOT_STARTED,
                                "无已提交批次且目标表为空，回填从未开始");
                    }
                    return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.UNCERTAIN,
                            "无批次日志但目标表有数据，来源不明，拒绝自动重跑");
                }
                long sourceCount = sandbox.countRows(run.sandboxSchema(), def.source(), def.where());
                long targetCount = sandbox.countRows(run.sandboxSchema(), def.target(), null);
                String cursor = step.cursorValue() == null ? "0" : step.cursorValue();
                if (sourceCount == targetCount) {
                    java.util.Map<String, String> identity = new java.util.LinkedHashMap<>();
                    def.mapping().keySet().forEach(k -> identity.put(k, k));
                    String sourceCk = sandbox.mappedChecksum(run.sandboxSchema(), def.source(), def.pk(), identity);
                    String targetCk = sandbox.mappedChecksum(run.sandboxSchema(), def.target(), def.pk(), def.mapping());
                    if (sourceCk.equals(targetCk)) {
                        store.updateStepStatus(run.id(), step.stepId(), States.STEP_COMMITTED);
                        journal(run, step.stepId(), Events.STEP_SKIPPED_COMMITTED,
                                map("proof", "COUNT_AND_CHECKSUM_EQUAL"));
                        return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.COMMITTED,
                                "行数与有序校验摘要均一致，回填已完整提交");
                    }
                }
                long loggedRows = store.listBatches(run.runUid(), step.stepId()).stream()
                        .mapToLong(com.example.migsb.store.Rows.Batch::rowCount).sum();
                if (targetCount > loggedRows) {
                    journal(run, step.stepId(), Events.BACKFILL_RESUME_TRUNCATED,
                            map("targetRows", targetCount, "loggedRows", loggedRows, "cursor", cursor));
                    sandbox.deleteTargetAfter(run.sandboxSchema(), def.target(), def.pk(), cursor);
                }
                return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.RESUMABLE,
                        "WAL 游标=" + cursor + "（已提交批次=" + batches + "），从稳定主键边界续跑，晚到行由关窗对账兜底");
            }
            case States.TYPE_DUAL_WRITE -> {
                boolean opened = stepJournal.stream().anyMatch(j -> Events.WINDOW_OPENED.equals(j.event()));
                boolean closed = stepJournal.stream().anyMatch(j -> Events.WINDOW_CLOSED.equals(j.event()));
                if (!opened) {
                    return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.NOT_STARTED,
                            "窗口未开启，可重新执行开窗");
                }
                if (closed) {
                    store.updateStepStatus(run.id(), step.stepId(), States.STEP_COMMITTED);
                    return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.COMMITTED,
                            "WINDOW_CLOSED 已落盘，双写步骤已提交");
                }
                boolean consistent = reconcileProbe(run, def);
                if (consistent) {
                    store.updateStepStatus(run.id(), step.stepId(), States.STEP_WINDOW_OPEN);
                    return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.WINDOW_OPEN,
                            "窗口在打开状态崩溃，新旧表当前一致，可继续双写或关窗");
                }
                store.updateStepStatus(run.id(), step.stepId(), States.STEP_WINDOW_OPEN);
                return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.WINDOW_OPEN,
                        "窗口在打开状态崩溃且存在仅旧表行，关窗时将自动对账补齐");
            }
            default -> {
                return new RecoveryVerdict(run.runUid(), step.stepId(), RecoveryVerdict.UNCERTAIN,
                        "未知步骤类型，无法判定");
            }
        }
    }

    private boolean reconcileProbe(Run run, MigrationSpec.StepDef def) {
        long oldCount = sandbox.countRows(run.sandboxSchema(), def.oldTable(), null);
        long newCount = sandbox.countRows(run.sandboxSchema(), def.newTable(), null);
        if (oldCount != newCount) {
            return false;
        }
        java.util.Map<String, String> identity = new java.util.LinkedHashMap<>();
        def.mapping().keySet().forEach(k -> identity.put(k, k));
        String oldCk = sandbox.mappedChecksum(run.sandboxSchema(), def.oldTable(), def.pk(), identity);
        String newCk = sandbox.mappedChecksum(run.sandboxSchema(), def.newTable(), def.pk(), def.mapping());
        return oldCk.equals(newCk);
    }
}
