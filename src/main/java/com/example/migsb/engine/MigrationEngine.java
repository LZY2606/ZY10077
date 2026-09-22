package com.example.migsb.engine;

import com.example.migsb.domain.Fp;
import com.example.migsb.domain.MigrationSpec;
import com.example.migsb.domain.States;
import com.example.migsb.store.ControlStore;
import com.example.migsb.store.Rows.Batch;
import com.example.migsb.store.Rows.Run;
import com.example.migsb.store.Rows.Step;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MigrationEngine extends EngineSupport {

    private final TransactionTemplate tx;

    public MigrationEngine(ControlStore store, SandboxService sandbox, ObjectMapper mapper,
                           CrashSimulator crash, TransactionTemplate tx) {
        super(store, sandbox, mapper, crash);
        this.tx = tx;
    }

    public synchronized Run createRun(String fingerprint, String armedFault) {
        var revision = store.findRevision(fingerprint)
                .orElseThrow(() -> new EngineException("定义指纹不存在，运行必须绑定具体版本: " + fingerprint));
        MigrationSpec spec;
        try {
            spec = mapper.readValue(revision.specJson(), MigrationSpec.class);
        } catch (Exception e) {
            throw new EngineException("定义无法解析: " + e.getMessage(), e);
        }
        if (armedFault != null && !armedFault.isBlank() && !"__finalize__".equals(armedFault)) {
            validateFaultKey(spec, armedFault);
        }
        String runUid = "r" + Fp.randomId(7);
        String schema = "SBX_" + Fp.randomId(8);
        sandbox.createSchema(schema);
        sandbox.buildSnapshot(schema, spec.snapshot().tables());
        for (var p : spec.preconditions()) {
            if (!sandbox.tableExists(schema, p.table())) {
                sandbox.dropSchema(schema);
                throw new EngineException("前置条件不满足: 表 " + p.table() + " 在快照中不存在");
            }
        }
        long runId = store.insertRun(runUid, revision.familyId(), fingerprint, schema,
                armedFault == null || armedFault.isBlank() ? null : armedFault);
        int seq = 0;
        for (var s : spec.steps()) {
            store.insertStep(runId, s.id(), s.type(), seq++, States.STEP_PENDING);
        }
        journal(store.findRunByUid(runUid).orElseThrow(), null, Events.RUN_STARTED,
                map("fingerprint", fingerprint, "snapshotTables", spec.snapshot().tables().size()));
        return store.findRunByUid(runUid).orElseThrow();
    }

    private void validateFaultKey(MigrationSpec spec, String armedFault) {
        int hash = armedFault.indexOf('#');
        if (hash <= 0) {
            throw new EngineException("故障点标识必须是 步骤id#故障点id: " + armedFault);
        }
        String stepId = armedFault.substring(0, hash);
        String faultId = armedFault.substring(hash + 1);
        var step = spec.steps().stream().filter(s -> s.id().equals(stepId)).findFirst()
                .orElseThrow(() -> new EngineException("故障点引用了未知步骤: " + stepId));
        boolean ok = step.faults().stream().anyMatch(f -> f.id().equals(faultId));
        if (!ok) {
            throw new EngineException("故障点 " + armedFault + " 未在定义中声明");
        }
    }

    public synchronized Map<String, Object> advance(String runUid) {
        Run run = store.findRunByUid(runUid)
                .orElseThrow(() -> new EngineException("运行不存在: " + runUid));
        if (States.RUN_ROLLING_BACK.equals(run.status())) {
            return continueRollback(run);
        }
        if (States.RUN_HALTED.equals(run.status())) {
            throw new EngineException("运行处于 HALTED，无法自动继续：必须先做出人工裁定（提交或补偿）");
        }
        if (States.RUN_COMMITTED.equals(run.status()) || States.RUN_ROLLED_BACK.equals(run.status())) {
            return map("status", run.status(), "message", "运行已处于终局状态");
        }
        if (!States.RUN_CREATED.equals(run.status()) && !States.RUN_RUNNING.equals(run.status())) {
            throw new EngineException("运行状态 " + run.status() + " 不允许推进");
        }
        store.updateRunStatus(runUid, States.RUN_RUNNING, null);
        run = store.findRunByUid(runUid).orElseThrow();
        MigrationSpec spec = specOf(run);

        for (Step step : steps(run)) {
            if (States.STEP_COMMITTED.equals(step.status())) {
                continue;
            }
            if (States.STEP_ROLLED_BACK.equals(step.status())) {
                continue;
            }
            if (States.STEP_WINDOW_OPEN.equals(step.status())) {
                return map("status", States.RUN_RUNNING, "waitingFor", "WINDOW_CLOSE",
                        "stepId", step.stepId(),
                        "message", "双写窗口已开启，请在写入页面制造双写流量后关闭窗口");
            }
            if (States.STEP_HALTED.equals(step.status())) {
                store.updateRunStatus(runUid, States.RUN_HALTED, null);
                throw new EngineException("步骤 " + step.stepId() + " 处于需补偿的中间态，必须人工裁定");
            }
            var stepDef = spec.steps().get(step.seqNo());
            journal(run, step.stepId(), Events.STEP_STARTED, map("type", step.stepType()));
            switch (step.stepType()) {
                case States.TYPE_DDL -> {
                    executeDdl(run, step, stepDef);
                }
                case States.TYPE_BACKFILL -> {
                    boolean finished = executeBackfill(run, step, stepDef);
                    if (!finished) {
                        return map("status", States.RUN_RUNNING, "message", "回填继续推进");
                    }
                }
                case States.TYPE_DUAL_WRITE -> {
                    openWindow(run, step, stepDef);
                    return map("status", States.RUN_RUNNING, "waitingFor", "WINDOW_CLOSE",
                            "stepId", step.stepId(), "message", "双写窗口已开启");
                }
                case States.TYPE_SIDE_EFFECT -> {
                    executeSideEffect(run, step, stepDef);
                }
                default -> throw new EngineException("未知步骤类型: " + step.stepType());
            }
        }
        return finalizeRun(run, spec);
    }

    private void executeDdl(Run run, Step step, MigrationSpec.StepDef def) {
        sandbox.execDdlInSchema(run.sandboxSchema(), def.ddl());
        journal(run, step.stepId(), Events.DDL_APPLIED, map("ddlDigest", Fp.sha256Hex(def.ddl().getBytes())));
        faultIfArmed(run, step, "AFTER_APPLY");
        store.updateStepStatus(run.id(), step.stepId(), States.STEP_COMMITTED);
        journal(run, step.stepId(), Events.STEP_COMMITTED, map("kind", "DDL"));
    }

    private void executeSideEffect(Run run, Step step, MigrationSpec.StepDef def) {
        // 副作用（如外部通知）先发生并落 WAL，但无探针可证明其效果；AFTER_APPLY 崩溃必须停住
        journal(run, step.stepId(), Events.SIDE_EFFECT_APPLIED, map("description", def.description()));
        faultIfArmed(run, step, "AFTER_APPLY");
        store.updateStepStatus(run.id(), step.stepId(), States.STEP_COMMITTED);
        journal(run, step.stepId(), Events.STEP_COMMITTED, map("kind", "SIDE_EFFECT"));
    }

    private boolean executeBackfill(Run run, Step step, MigrationSpec.StepDef def) {
        int batchSize = def.batchSize() == null ? 100 : def.batchSize();
        String cursor = step.cursorValue() == null ? "0" : step.cursorValue();
        int batchNo = (int) store.listBatches(run.runUid(), step.stepId()).size();
        while (true) {
            final String curCursor = cursor;
            final int curBatch = batchNo;
            List<Map<String, Object>> rows =
                    sandbox.rowsAfter(run.sandboxSchema(), def.source(), def.pk(), curCursor, batchSize, def.where());
            boolean committed = tx.execute(status -> {
                if (!rows.isEmpty()) {
                    sandbox.mergeBatch(run.sandboxSchema(), def.target(), def.pk(), def.mapping(), rows);
                }
                String first = rows.isEmpty() ? null : String.valueOf(rows.get(0).get(def.pk()));
                String last = rows.isEmpty() ? null : String.valueOf(rows.get(rows.size() - 1).get(def.pk()));
                String checksum = sandbox.checksumOfRows(rows, new ArrayList<>(def.mapping().keySet()));
                store.insertBatch(run.runUid(), step.stepId(), curBatch, first, last, rows.size(), checksum);
                journal(run, step.stepId(), Events.BACKFILL_BATCH_COMMITTED,
                        map("batchNo", curBatch, "rows", rows.size(), "pkFrom", first, "pkTo", last,
                                "checksum", checksum));
                if (rows.isEmpty()) {
                    store.updateStepCursor(run.id(), step.stepId(), curCursor);
                } else {
                    store.updateStepCursor(run.id(), step.stepId(), last);
                }
                return true;
            });
            faultIfArmed(run, step, "MID_BATCH");
            if (rows.isEmpty()) {
                break;
            }
            cursor = String.valueOf(rows.get(rows.size() - 1).get(def.pk()));
            batchNo++;
        }
        faultIfArmed(run, step, "AFTER_BATCHES");
        long sourceCount = sandbox.countRows(run.sandboxSchema(), def.source(), def.where());
        long targetCount = sandbox.countRows(run.sandboxSchema(), def.target(), null);
        if (sourceCount != targetCount) {
            throw new EngineException("回填结束但行数不一致: 源=" + sourceCount + " 目标=" + targetCount);
        }
        store.updateStepStatus(run.id(), step.stepId(), States.STEP_COMMITTED);
        journal(run, step.stepId(), Events.STEP_COMMITTED,
                map("kind", "BACKFILL", "rows", targetCount, "batches",
                        store.listBatches(run.runUid(), step.stepId()).size()));
        return true;
    }

    private void openWindow(Run run, Step step, MigrationSpec.StepDef def) {
        // 开窗前先把存量行对账补齐（MERGE 幂等），保证窗口开始即一致
        sandbox.mergeMapped(run.sandboxSchema(), def.oldTable(), def.newTable(), def.pk(), def.mapping());
        faultIfArmed(run, step, "BEFORE_OPEN");
        store.updateStepStatus(run.id(), step.stepId(), States.STEP_WINDOW_OPEN);
        journal(run, step.stepId(), Events.WINDOW_OPENED,
                map("oldTable", def.oldTable(), "newTable", def.newTable()));
        faultIfArmed(run, step, "AFTER_OPEN");
    }

    /** 双写窗口期间的一次写入，applyTo: OLD / NEW / BOTH。 */
    public synchronized Map<String, Object> windowWrite(String runUid, String stepId,
                                                        String applyTo, Map<String, Object> sourceRow) {
        Run run = mustOwnWindow(runUid, stepId);
        MigrationSpec.StepDef def = stepDef(run, stepId);
        if (sourceRow == null || sourceRow.get(def.pk()) == null) {
            throw new EngineException("双写数据必须包含主键 " + def.pk());
        }
        String pk = String.valueOf(sourceRow.get(def.pk()));
        journal(run, stepId, Events.WINDOW_WRITE, map("pk", pk, "applyTo", applyTo));
        switch (applyTo) {
            case States.APPLY_BOTH -> {
                sandbox.mergeMappedRow(run.sandboxSchema(), def, sourceRow, true, true);
            }
            case States.APPLY_OLD -> {
                sandbox.mergeMappedRow(run.sandboxSchema(), def, sourceRow, true, false);
            }
            case States.APPLY_NEW -> {
                sandbox.mergeMappedRow(run.sandboxSchema(), def, sourceRow, false, true);
            }
            default -> throw new EngineException("applyTo 必须是 OLD/NEW/BOTH");
        }
        store.insertWindowWrite(run.runUid(), stepId, pk, sandbox.toJson(sourceRow), applyTo);
        return map("pk", pk, "appliedTo", applyTo, "consistent",
                checkWindowConsistency(run, def));
    }

    public synchronized void closeWindow(String runUid, String stepId) {
        Run run = store.findRunByUid(runUid)
                .orElseThrow(() -> new EngineException("运行不存在: " + runUid));
        Step step = store.findStep(run.id(), stepId)
                .orElseThrow(() -> new EngineException("步骤不存在: " + stepId));
        if (!States.STEP_WINDOW_OPEN.equals(step.status())) {
            throw new EngineException("步骤 " + stepId + " 当前不处于 WINDOW_OPEN");
        }
        MigrationSpec.StepDef def = stepDef(run, stepId);
        // 关窗补偿扫描：把仅写入旧表的行补齐到新表（含回填后到达、主键低于游标边界的晚到行）
        sandbox.mergeMapped(run.sandboxSchema(), def.oldTable(), def.newTable(), def.pk(), def.mapping());
        faultIfArmed(run, step, "BEFORE_CLOSE");
        long oldCount = sandbox.countRows(run.sandboxSchema(), def.oldTable(), null);
        long newCount = sandbox.countRows(run.sandboxSchema(), def.newTable(), null);

        boolean consistent = oldCount == newCount
                && checksumEqual(run, def.oldTable(), def.newTable(), def);
        journal(run, stepId, Events.WINDOW_RECONCILED,
                map("oldCount", oldCount, "newCount", newCount, "consistent", consistent));
        if (!consistent) {
            throw new EngineException("关窗对账失败：旧表=" + oldCount + " 新表=" + newCount
                    + "（行数或规范化校验摘要不一致）");
        }
        faultIfArmed(run, step, "AFTER_RECONCILE");
        store.updateStepStatus(run.id(), stepId, States.STEP_COMMITTED);
        journal(run, stepId, Events.STEP_COMMITTED, map("kind", "DUAL_WRITE"));
        journal(run, stepId, Events.WINDOW_CLOSED, map("oldCount", oldCount, "newCount", newCount));
        faultIfArmed(run, step, "AFTER_CLOSE");
    }

    private Run mustOwnWindow(String runUid, String stepId) {
        Run run = store.findRunByUid(runUid)
                .orElseThrow(() -> new EngineException("运行不存在: " + runUid));
        Step step = store.findStep(run.id(), stepId)
                .orElseThrow(() -> new EngineException("步骤不存在: " + stepId));
        if (!States.STEP_WINDOW_OPEN.equals(step.status())) {
            throw new EngineException("步骤 " + stepId + " 当前不处于 WINDOW_OPEN，无法执行窗口操作");
        }
        return run;
    }

    private MigrationSpec.StepDef stepDef(Run run, String stepId) {
        return specOf(run).steps().stream()
                .filter(s -> s.id().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new EngineException("步骤定义缺失: " + stepId));
    }

    private boolean checkWindowConsistency(Run run, MigrationSpec.StepDef def) {
        long oldCount = sandbox.countRows(run.sandboxSchema(), def.oldTable(), null);
        long newCount = sandbox.countRows(run.sandboxSchema(), def.newTable(), null);
        return oldCount == newCount && checksumEqual(run, def.oldTable(), def.newTable(), def);
    }

    private boolean checksumEqual(Run run, String oldTable, String newTable, MigrationSpec.StepDef def) {
        java.util.Map<String, String> identity = new java.util.LinkedHashMap<>();
        def.mapping().keySet().forEach(k -> identity.put(k, k));
        String oldCk = sandbox.mappedChecksum(run.sandboxSchema(), oldTable, def.pk(), identity);
        String newCk = sandbox.mappedChecksum(run.sandboxSchema(), newTable, def.pk(), def.mapping());
        return oldCk.equals(newCk);
    }

    // ---------- finalization ----------

    private Map<String, Object> finalizeRun(Run run, MigrationSpec spec) {
        List<String> failures = new ArrayList<>();

        for (var inv : spec.invariants()) {
            boolean passed;
            String detail;
            switch (inv.type()) {
                case "ROW_COUNT_EQUAL" -> {
                    long a = sandbox.countRows(run.sandboxSchema(), inv.source(), null);
                    long b = sandbox.countRows(run.sandboxSchema(), inv.target(), null);
                    passed = a == b;
                    detail = "source=" + a + " target=" + b;
                }
                case "ORDERED_CHECKSUM_EQUAL" -> {
                    String a = sandbox.orderedChecksum(run.sandboxSchema(), inv.source(), inv.pk(), inv.columns());
                    String b = sandbox.orderedChecksum(run.sandboxSchema(), inv.target(), inv.pk(), inv.columns());
                    passed = a.equals(b);
                    detail = "source=" + a.substring(0, 12) + " target=" + b.substring(0, 12);
                }
                default -> {
                    Object a = sandbox.scalar(run.sandboxSchema(), inv.sqlA());
                    Object b = sandbox.scalar(run.sandboxSchema(), inv.sqlB());
                    passed = String.valueOf(a).equals(String.valueOf(b));
                    detail = "A=" + a + " B=" + b;
                }
            }
            store.insertInvResult(run.runUid(), inv.id(), passed, detail);
            journal(run, null, Events.INVARIANT_CHECK,
                    map("invariant", inv.id(), "passed", passed, "detail", detail));
            if (!passed) {
                failures.add("不变量 " + inv.id() + " 未通过（" + detail + "）");
            }
        }

        for (var rp : spec.readPaths()) {
            var oldRows = sandbox.query(run.sandboxSchema(), rp.oldSql());
            var newRows = sandbox.query(run.sandboxSchema(), rp.newSql());
            String oldJson = sandbox.rowsJson(oldRows);
            String newJson = sandbox.rowsJson(newRows);
            String oldCk = Fp.sha256Hex(oldJson.getBytes());
            String newCk = Fp.sha256Hex(newJson.getBytes());
            boolean matched = oldCk.equals(newCk);
            store.insertReadCheck(run.runUid(), rp.label(), oldJson, newJson, matched);
            journal(run, null, Events.READ_CHECK,
                    map("label", rp.label(), "matched", matched, "old", oldCk, "new", newCk));
            if (!matched) {
                failures.add("读路径 " + rp.label() + " 旧读与新读结果不一致");
            }
        }

        if (!failures.isEmpty()) {
            String msg = "完成判定失败（命令返回零不代表迁移成功）: " + String.join("; ", failures);
            store.updateRunStatus(run.runUid(), States.RUN_FAILED, msg);
            journal(run, null, Events.RUN_HALTED, map("reason", "FINALIZATION_FAILED", "failures", failures));
            throw new EngineException(msg);
        }

        if ("__finalize__".equals(run.armedFault())) {
            crashAtFinalize(run);
        }
        store.updateRunTerminalEvent(run.runUid(), Events.RUN_COMPLETED, States.RUN_COMMITTED);
        journal(run, null, Events.RUN_COMPLETED,
                map("invariants", spec.invariants().size(), "readPaths", spec.readPaths().size()));
        return map("status", States.RUN_COMMITTED, "message", "结构、数据不变量、读路径与日志终局全部满足");
    }

    // ---------- human decision for unprovable intermediate states ----------

    public synchronized Map<String, Object> decide(String runUid, String decision, String note) {
        Run run = store.findRunByUid(runUid)
                .orElseThrow(() -> new EngineException("运行不存在: " + runUid));
        if (!States.RUN_HALTED.equals(run.status())) {
            throw new EngineException("只有 HALTED 运行可以接受人工裁定，当前=" + run.status());
        }
        if (!States.DECISION_COMMIT.equals(decision) && !States.DECISION_COMPENSATE.equals(decision)) {
            throw new EngineException("decision 必须是 COMMIT 或 COMPENSATE");
        }
        List<Step> halted = steps(run).stream()
                .filter(s -> States.STEP_HALTED.equals(s.status())).toList();
        journal(run, null, Events.HUMAN_DECISION,
                map("decision", decision, "note", note == null ? "" : note,
                        "haltedSteps", halted.stream().map(Step::stepId).toList()));
        if (States.DECISION_COMMIT.equals(decision)) {
            for (Step s : halted) {
                store.updateStepStatus(run.id(), s.stepId(), States.STEP_COMMITTED);
                journal(run, s.stepId(), Events.STEP_COMMITTED,
                        map("kind", s.stepType(), "by", "HUMAN_DECISION"));
            }
            store.updateRunDecision(runUid, States.DECISION_COMMIT, note, States.RUN_RUNNING);
            return advance(runUid);
        }
        store.updateRunDecision(runUid, States.DECISION_COMPENSATE, note, States.RUN_ROLLING_BACK);
        return rollback(runUid);
    }

    // ---------- rollback ----------

    public synchronized Map<String, Object> rollback(String runUid) {
        Run run = store.findRunByUid(runUid)
                .orElseThrow(() -> new EngineException("运行不存在: " + runUid));
        if (States.RUN_ROLLED_BACK.equals(run.status()) || States.RUN_COMMITTED.equals(run.status())) {
            throw new EngineException("终局状态不允许回滚: " + run.status());
        }
        store.updateRunStatus(runUid, States.RUN_ROLLING_BACK, null);
        journal(run, null, Events.ROLLBACK_STARTED, map("at", run.status()));
        return continueRollback(run);
    }

    private Map<String, Object> continueRollback(Run run) {
        MigrationSpec spec = specOf(run);
        List<Step> ordered = steps(run);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Step step = ordered.get(i);
            if (States.STEP_ROLLED_BACK.equals(step.status()) || States.STEP_PENDING.equals(step.status())) {
                continue;
            }
            var def = spec.steps().get(step.seqNo());
            compensateStep(run, step, def);
            store.updateStepStatus(run.id(), step.stepId(), States.STEP_ROLLED_BACK);
            journal(run, step.stepId(), Events.STEP_ROLLED_BACK, map("kind", step.stepType()));
        }
        store.updateRunTerminalEvent(run.runUid(), Events.RUN_ROLLED_BACK, States.RUN_ROLLED_BACK);
        journal(run, null, Events.RUN_ROLLED_BACK, map("terminal", true));
        return map("status", States.RUN_ROLLED_BACK, "message", "已按逆序补偿全部步骤");
    }

    private void compensateStep(Run run, Step step, MigrationSpec.StepDef def) {
        switch (step.stepType()) {
            case States.TYPE_DDL -> {
                if (def.compensate() != null && !def.compensate().isBlank()) {
                    sandbox.execDdlInSchema(run.sandboxSchema(), def.compensate());
                }
            }
            case States.TYPE_BACKFILL -> {
                sandbox.deleteTargetAll(run.sandboxSchema(), def.target());
            }
            case States.TYPE_DUAL_WRITE -> {
                // 双写窗口不复制数据，回滚无需删除新表数据；窗口期间写入旧表的数据保留
                if (States.STEP_WINDOW_OPEN.equals(step.status())) {
                    journal(run, step.stepId(), Events.WINDOW_CLOSED, map("reason", "ROLLBACK"));
                }
            }
            case States.TYPE_SIDE_EFFECT -> {
                if (def.compensate() != null && !def.compensate().isBlank()) {
                    sandbox.execDdlInSchema(run.sandboxSchema(), def.compensate());
                }
            }
            default -> throw new EngineException("未知步骤类型: " + step.stepType());
        }
    }
}
