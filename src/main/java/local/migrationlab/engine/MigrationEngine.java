package local.migrationlab.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.migrationlab.domain.CrashInjector;
import local.migrationlab.domain.DefinitionStore;
import local.migrationlab.domain.RunStore;
import local.migrationlab.support.ApiException;
import local.migrationlab.support.Identifiers;
import local.migrationlab.support.Json;
import local.migrationlab.support.Sandbox;
import local.migrationlab.support.SimulatedProcessExitException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MigrationEngine {
    private final DefinitionStore definitions;
    private final RunStore runs;
    private final PhysicalMigrator physical;
    private final PreconditionChecker checker;
    private final CrashInjector crash;
    private final Sandbox sandbox;

    public MigrationEngine(DefinitionStore definitions, RunStore runs, PhysicalMigrator physical,
                           PreconditionChecker checker, CrashInjector crash, Sandbox sandbox) {
        this.definitions = definitions;
        this.runs = runs;
        this.physical = physical;
        this.checker = checker;
        this.crash = crash;
        this.sandbox = sandbox;
    }

    public Map<String, Object> createRun(String fingerprint, String name, String exitMode) {
        Map<String, Object> definitionView = definitions.byFingerprint(fingerprint);
        JsonNode definition = (JsonNode) definitionView.get("content");
        String schema = "r_" + UUID.randomUUID().toString().replace("-", "");
        String runId = UUID.randomUUID().toString();
        String snapshotFingerprint = Json.sha256(Json.canonical(definition.path("snapshot")));
        physical.initializeSandbox(schema, definition);
        Map<String, Object> run = runs.createRun(Map.of(
                "id", runId,
                "definitionId", definitionView.get("definitionId"),
                "fingerprint", fingerprint,
                "ruleVersion", Json.RULE_VERSION,
                "name", name == null || name.isBlank()
                        ? Identifiers.text(definition, "title") + " " + Instant.now().toString() : name,
                "schemaName", schema,
                "status", "NEVER_STARTED",
                "baseSnapshotFingerprint", snapshotFingerprint
        ));
        runs.appendEvent(runId, "RUN_CREATED", null, null, Map.of(
                "definitionFingerprint", fingerprint,
                "schema", schema,
                "baseSnapshotFingerprint", snapshotFingerprint,
                "exitMode", exitMode == null ? "simulate" : exitMode
        ));
        return run;
    }

    public Map<String, Object> start(String runId, String faultPoint, String exitMode) {
        Map<String, Object> run = requireRunnable(runId);
        arm(runId, faultPoint, exitMode);
        run = runs.getRun(runId);
        JsonNode definition = definition(run);
        try {
            runFrom(run, definition, 0, 0L);
            return runs.getRun(runId);
        } catch (SimulatedProcessExitException e) {
            return runs.getRun(runId);
        }
    }

    public Map<String, Object> arm(String runId, String faultPoint, String exitMode) {
        Map<String, Object> run = runs.getRun(runId);
        if (!List.of("NEVER_STARTED", "AWAITING_DECISION", "READY", "PAUSED", "BLOCKED").contains(run.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "只有可继续状态才能设置故障点");
        }
        if (faultPoint != null && !faultPoint.isBlank()) {
            runs.armFailure(runId, faultPoint, exitMode == null || exitMode.isBlank() ? "simulate" : exitMode);
            runs.appendEvent(runId, "FAILURE_ARMED", null, faultPoint, Map.of(
                    "faultPoint", faultPoint, "exitMode", exitMode == null ? "simulate" : exitMode));
        }
        return runs.getRun(runId);
    }

    public Map<String, Object> resume(String runId) {
        Map<String, Object> run = runs.getRun(runId);
        String status = (String) run.get("status");
        if (!List.of("READY", "AWAITING_DECISION", "PAUSED", "RUNNING").contains(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "当前状态 " + status + " 不允许安全继续");
        }
        JsonNode definition = definition(run);
        int stepIndex = stepIndex(definition, (String) run.get("currentStep"));
        long cursor = run.get("cursorId") == null ? 0L : ((Number) run.get("cursorId")).longValue();
        try {
            runFrom(run, definition, stepIndex, cursor);
            return runs.getRun(runId);
        } catch (SimulatedProcessExitException e) {
            return runs.getRun(runId);
        }
    }

    public Map<String, Object> rollback(String runId) {
        Map<String, Object> run = runs.getRun(runId);
        String status = (String) run.get("status");
        if ("ROLLED_BACK".equals(status) || "COMPLETED".equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "当前状态 " + status + " 不能回滚");
        }
        JsonNode definition = definition(run);
        int stepIndex = stepIndex(definition, (String) run.get("currentStep"));
        runs.appendEvent(runId, "ROLLBACK_STARTED", null, null, Map.of(
                "fromStep", String.valueOf(run.get("currentStep")), "status", status));
        try (Connection connection = sandbox.connection((String) run.get("schemaName"))) {
            for (int index = stepIndex; index >= 0; index--) {
                JsonNode step = definition.path("steps").get(index);
                reverseStep(connection, (String) run.get("schemaName"), step, runId);
                runs.appendEvent(runId, "STEP_ROLLED_BACK", step.path("id").asText(), null,
                        Map.of("type", step.path("type").asText()));
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Physical rollback failed", e);
        }
        runs.updateState(runId, "ROLLED_BACK", null, null, "所有可补偿步骤已按逆序回滚");
        runs.appendEvent(runId, "ROLLBACK_COMMITTED", null, null, Map.of("terminal", true));
        return runs.getRun(runId);
    }

    public void recoverAtStartup() {
        for (Map<String, Object> candidate : runs.listRuns()) {
            String runId = (String) candidate.get("id");
            Map<String, Object> run = runs.getRun(runId);
            String status = (String) run.get("status");
            if (("COMPLETED".equals(status) || "ROLLED_BACK".equals(status))
                    || ("NEVER_STARTED".equals(status) && run.get("failureArm") == null)) {
                continue;
            }
            Map<String, Object> recovery = diagnose(run);
            if ("READY".equals(status) && (run.get("failureArm") == null)) {
                continue;
            }
            String recoveredStatus = (String) recovery.get("status");
            runs.clearFailureArm(runId);
            runs.updateState(runId, recoveredStatus, (String) recovery.get("stepId"),
                    recovery.get("cursorId") == null ? null : ((Number) recovery.get("cursorId")).longValue(),
                    (String) recovery.get("diagnosis"));
            runs.appendEvent(runId, "RECOVERY_DECISION", (String) recovery.get("stepId"),
                    (String) recovery.get("phase"), recovery);
        }
    }

    public Map<String, Object> detail(String runId) {
        Map<String, Object> run = runs.getRun(runId);
        JsonNode definition = definition(run);
        Map<String, Object> detail = new LinkedHashMap<>(run);
        detail.put("definition", definition);
        detail.put("events", runs.events(runId));
        detail.put("batches", runs.batches(runId));
        detail.put("verifications", runs.verifications(runId));
        detail.put("failurePoints", failurePoints(definition));
        return detail;
    }

    public Map<String, Object> testInsert(String runId, String email) {
        Map<String, Object> run = runs.getRun(runId);
        String schema = (String) run.get("schemaName");
        String value = email == null || email.isBlank()
                ? "new." + UUID.randomUUID().toString().substring(0, 8) + "@example.test" : email;
        try (Connection connection = sandbox.connection(schema)) {
            physical.insertSource(connection, schema, value);
            connection.commit();
            long source = physical.count(connection, schema, "customers");
            boolean targetExists = physical.tableExists(connection, schema, "customers_v2");
            long target = targetExists ? physical.count(connection, schema, "customers_v2") : 0L;
            connection.rollback();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("email", value);
            payload.put("sourceCount", source);
            payload.put("targetCount", target);
            payload.put("dualWriteObserved", targetExists && source == target);
            runs.appendEvent(runId, "TEST_INSERT", null, null, payload);
            return payload;
        } catch (SQLException e) {
            throw new IllegalStateException("Test insert failed", e);
        }
    }

    private void runFrom(Map<String, Object> run, JsonNode definition, int stepIndex, long cursor) {
        String runId = (String) run.get("id");
        for (int index = stepIndex; index < definition.path("steps").size(); index++) {
            JsonNode step = definition.path("steps").get(index);
            executeStep(runId, (String) run.get("schemaName"), definition, step,
                    index, index == stepIndex ? cursor : 0L, index == stepIndex);
        }
        Map<String, Object> runAfter = runs.getRun(runId);
        if (finalTerminalProof(runAfter, definition)) {
            runs.complete(runId);
            runs.appendEvent(runId, "RUN_COMPLETED", null, null, Map.of(
                    "structure", true, "dataInvariants", true, "logTerminal", true));
        } else {
            Map<String, Object> observation = observe((String) runAfter.get("schemaName"));
            throw new ApiException(HttpStatus.CONFLICT, "命令完成但终局证明不足，拒绝标记完成: "
                    + Json.write(Map.of(
                    "finalVerification", runs.verifications(runId).stream()
                            .filter(item -> "final_invariants".equals(item.get("stepId"))).toList(),
                    "hashChain", verifyEventChain(runId),
                    "physicalObservation", observation)));
        }
    }

    private void executeStep(String runId, String schema, JsonNode definition, JsonNode step,
                             int stepIndex, long resumeCursor, boolean resumingCurrent) {
        String stepId = step.path("id").asText();
        String type = step.path("type").asText();
        Map<String, Object> run = runs.getRun(runId);
        if (!resumingCurrent) {
            runs.updateState(runId, "RUNNING", stepId, null, null);
            runs.appendEvent(runId, "STEP_STARTED", stepId, "before", Map.of("type", type));
        }
        crashPoint(run, stepId + ".before");
        try (Connection connection = sandbox.connection(schema)) {
            switch (type) {
                case "create_target" -> createTarget(runId, schema, step, connection, resumingCurrent);
                case "enable_dual_write" -> enableDualWrite(runId, schema, step, connection, resumingCurrent);
                case "backfill" -> backfill(runId, schema, step, connection,
                        resumingCurrent ? resumeCursor : 0L, resumingCurrent);
                case "verify" -> verify(runId, schema, definition, step, connection, false, resumingCurrent);
                case "cutover" -> cutover(runId, schema, step, connection, resumingCurrent);
                case "final_verify" -> finalVerify(runId, schema, definition, step, connection, resumingCurrent);
                default -> throw new ApiException(HttpStatus.BAD_REQUEST, "未知步骤类型: " + type);
            }
            connection.commit();
        } catch (SimulatedProcessExitException e) {
            throw e;
        } catch (SQLException e) {
            throw new IllegalStateException("Physical migration step failed: " + stepId, e);
        }
        crashPoint(runs.getRun(runId), stepId + ".after");
        runs.appendEvent(runId, "STEP_COMMITTED", stepId, "after",
                Map.of("stepIndex", stepIndex, "type", type));
        runs.updateState(runId, "READY", nextStepId(definition, stepIndex), null, null);
    }

    private void createTarget(String runId, String schema, JsonNode step, Connection connection,
                              boolean resuming) throws SQLException {
        boolean exists = physical.tableExists(connection, schema, "customers_v2");
        if (!resuming) {
            CheckResult preconditions = checker.checkAll(connection, schema, step.get("preconditions"));
            if (!preconditions.passed()) {
                throw new ApiException(HttpStatus.CONFLICT, preconditions.summary());
            }
        }
        if (!exists) {
            physical.createTarget(connection, schema);
        }
        connection.commit();
        crashPoint(runs.getRun(runId), step.path("id").asText() + ".after");
        appendPhysicalEvidence(runId, step, exists ? "SKIPPED_ALREADY_COMMITTED" : "CREATED", Map.of(
                "table", "customers_v2", "observedAfter", true));
    }

    private void enableDualWrite(String runId, String schema, JsonNode step, Connection connection,
                                 boolean resuming) throws SQLException {
        boolean trigger = physical.triggerExists(connection, schema, "trg_customers_dualwrite");
        if (!resuming) {
            CheckResult preconditions = checker.checkAll(connection, schema, step.get("preconditions"));
            if (!preconditions.passed()) {
                throw new ApiException(HttpStatus.CONFLICT, preconditions.summary());
            }
        }
        if (!trigger) {
            physical.createDualWriteTrigger(connection, schema);
        }
        connection.commit();
        crashPoint(runs.getRun(runId), step.path("id").asText() + ".after");
        appendPhysicalEvidence(runId, step, trigger ? "SKIPPED_ALREADY_COMMITTED" : "TRIGGER_INSTALLED", Map.of(
                "trigger", "trg_customers_dualwrite", "observedAfter", true));
    }

    private void backfill(String runId, String schema, JsonNode step, Connection connection,
                          long resumeCursor, boolean resuming) throws SQLException {
        String stepId = step.path("id").asText();
        int batchSize = step.path("batchSize").asInt(step.path("batchSize").asInt(25));
        long cursor = resumeCursor;
        int batchNo = physical.batchNumberForCursor(cursor, batchSize) - 1;
        while (true) {
            PhysicalMigrator.BatchResult result = physical.backfillBatch(connection, schema, cursor, batchSize);
            connection.commit();
            runs.updateState(runId, "RUNNING", stepId, cursor,
                    "批次 " + (batchNo + 1) + " 物理效果已提交，等待可恢复日志确认");
            crashPoint(runs.getRun(runId), stepId + ".batch_after");
            batchNo++;
            long nextCursor = result.lastId();
            runs.saveBatch(runId, stepId, batchNo, nextCursor, result.sourceRows(),
                    result.insertedRows(), result.checksum(), "COMMITTED", resuming);
            runs.updateState(runId, "READY", stepId, nextCursor,
                    "批次 " + batchNo + " 已提交，稳定主键边界 id <= " + nextCursor);
            runs.appendEvent(runId, "BATCH_COMMITTED", stepId, "batch_after", Map.of(
                    "batchNo", batchNo,
                    "sourceRows", result.sourceRows(),
                    "insertedRows", result.insertedRows(),
                    "lastId", nextCursor,
                    "checksum", result.checksum()
            ));
            crashPoint(runs.getRun(runId), stepId + ".after");
            if (result.complete()) {
                break;
            }
            cursor = nextCursor;
        }
    }

    private void verify(String runId, String schema, JsonNode definition, JsonNode step,
                        Connection connection, boolean afterCutover, boolean resuming) throws SQLException {
        CheckResult stepChecks = checker.checkAll(connection, schema, step.get("preconditions"));
        if (!stepChecks.passed() && !resuming) {
            throw new ApiException(HttpStatus.CONFLICT, stepChecks.summary());
        }
        CheckResult readPaths = checker.verifyData(connection, schema, afterCutover);
        crashPoint(runs.getRun(runId), step.path("id").asText() + ".after");
        connection.commit();
        runs.saveVerification(runId, step.path("id").asText(), "READ_PATH_COMPARISON",
                readPaths.passed(), readPaths.summary(), readPaths.detail(), fingerprint(runId));
        appendPhysicalEvidence(runId, step, readPaths.passed() ? "VERIFIED" : "VERIFICATION_FAILED",
                readPaths.detail());
        if (!readPaths.passed()) {
            throw new ApiException(HttpStatus.CONFLICT, readPaths.summary());
        }
    }

    private void cutover(String runId, String schema, JsonNode step, Connection connection,
                         boolean resuming) throws SQLException {
        boolean oldState = physical.tableExists(connection, schema, "customers")
                && physical.tableExists(connection, schema, "customers_v2");
        boolean switchedState = physical.tableExists(connection, schema, "customers_old")
                && physical.tableExists(connection, schema, "customers")
                && !physical.triggerExists(connection, schema, "trg_customers_dualwrite");
        if (!resuming) {
            CheckResult preconditions = checker.checkAll(connection, schema, step.get("preconditions"));
            if (!preconditions.passed()) {
                throw new ApiException(HttpStatus.CONFLICT, preconditions.summary());
            }
        }
        if (!switchedState) {
            physical.cutover(connection, schema);
        }
        connection.commit();
        crashPoint(runs.getRun(runId), step.path("id").asText() + ".after");
        appendPhysicalEvidence(runId, step, switchedState ? "SKIPPED_ALREADY_COMMITTED" : "CUTOVER_COMMITTED",
                Map.of("oldTableRenamed", true, "triggerRemoved", true, "observedBefore", oldState));
    }

    private void finalVerify(String runId, String schema, JsonNode definition, JsonNode step,
                             Connection connection, boolean resuming) throws SQLException {
        CheckResult readPaths = checker.verifyData(connection, schema, true);
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("customersExists", physical.tableExists(connection, schema, "customers"));
        structure.put("customersOldExists", physical.tableExists(connection, schema, "customers_old"));
        structure.put("customersV2Absent", !physical.tableExists(connection, schema, "customers_v2"));
        structure.put("dualWriteTriggerAbsent", !physical.triggerExists(connection, schema, "trg_customers_dualwrite"));
        boolean structurePassed = structure.values().stream().allMatch(Boolean.TRUE::equals);
        crashPoint(runs.getRun(runId), step.path("id").asText() + ".after");
        connection.commit();
        CheckResult structureResult = structurePassed
                ? CheckResult.ok("终局结构符合切换后状态", structure)
                : CheckResult.fail("终局结构不完整", structure);
        runs.saveVerification(runId, step.path("id").asText(), "FINAL_INVARIANTS",
                structureResult.passed() && readPaths.passed(),
                structureResult.summary() + "；" + readPaths.summary(),
                Map.of("structure", structure, "data", readPaths.detail()), fingerprint(runId));
        appendPhysicalEvidence(runId, step,
                structureResult.passed() && readPaths.passed() ? "FINAL_PROOF_COMMITTED" : "FINAL_PROOF_FAILED",
                Map.of("structure", structure, "data", readPaths.detail()));
        if (!structureResult.passed() || !readPaths.passed()) {
            throw new ApiException(HttpStatus.CONFLICT, "结构或数据不变量不满足终局条件");
        }
    }

    private void reverseStep(Connection connection, String schema, JsonNode step, String runId) throws SQLException {
        String type = step.path("type").asText();
        switch (type) {
            case "final_verify" -> {
            }
            case "cutover" -> {
                boolean switched = physical.tableExists(connection, schema, "customers_old")
                        && physical.tableExists(connection, schema, "customers")
                        && !physical.tableExists(connection, schema, "customers_v2");
                if (switched) {
                    physical.reverseCutover(connection, schema);
                }
            }
            case "verify", "backfill" -> {
            }
            case "enable_dual_write" -> physical.dropTrigger(connection, schema);
            case "create_target" -> physical.dropTarget(connection, schema);
            default -> throw new ApiException(HttpStatus.CONFLICT, "未知步骤缺少补偿规则: " + type);
        }
    }

    private Map<String, Object> diagnose(Map<String, Object> run) {
        String runId = (String) run.get("id");
        String schema = (String) run.get("schemaName");
        JsonNode definition = definition(run);
        String currentStepId = (String) run.get("currentStep");
        if (currentStepId == null) {
            return recoveryMap(null, null, null, "READY", "没有未完成步骤；可继续终局判定");
        }
        JsonNode step = findStep(definition, currentStepId);
        String type = step.path("type").asText();
        List<Map<String, Object>> events = runs.events(runId);
        boolean prepared = events.stream().anyMatch(event ->
                currentStepId.equals(String.valueOf(event.get("stepId")))
                        && List.of("STEP_STARTED", "STEP_RESUMED").contains(event.get("type")));
        Map<String, Object> observation = observe(schema);
        boolean target = (Boolean) observation.get("targetExists");
        boolean trigger = (Boolean) observation.get("triggerExists");
        boolean old = (Boolean) observation.get("oldExists");
        long targetCount = ((Number) observation.get("targetCount")).longValue();
        long sourceCount = ((Number) observation.get("sourceCount")).longValue();
        Long cursor = run.get("cursorId") == null ? null : ((Number) run.get("cursorId")).longValue();

        if (!prepared) {
            return switch (type) {
                case "create_target" -> target
                        ? recoveryMap(currentStepId, "after", null, "READY",
                        "无 PREPARED 日志但新表已存在；按已提交 DDL 跳到下一步", observation)
                        : recoveryMap(currentStepId, "before", null, "NEVER_STARTED",
                        "未找到步骤 PREPARED 日志且新表不存在；按从未开始继续", observation);
                case "enable_dual_write" -> trigger
                        ? recoveryMap(currentStepId, "after", null, "READY",
                        "无 PREPARED 日志但触发器存在；按已提交 DDL 跳到下一步", observation)
                        : recoveryMap(currentStepId, "before", null, "READY",
                        "未找到步骤 PREPARED 日志且触发器不存在；可重新执行", observation);
                default -> recoveryMap(currentStepId, "before", null, "READY",
                        "未找到步骤 PREPARED 日志；步骤可幂等重新执行", observation);
            };
        }

        return switch (type) {
            case "create_target" -> target
                    ? recoveryMap(currentStepId, "after", null, "READY",
                            "新表存在，物理效果已提交；补偿或继续都会跳过重复 DDL", observation)
                    : recoveryMap(currentStepId, "before", null, "READY",
                            "新表不存在且 PREPARED 后无物理效果；可安全重试", observation);
            case "enable_dual_write" -> trigger
                    ? recoveryMap(currentStepId, "after", null, "READY",
                            "触发器存在，安装已提交；继续时跳过重复安装", observation)
                    : recoveryMap(currentStepId, "before", null, "READY",
                            "触发器不存在；可安全重试安装", observation);
            case "backfill" -> {
                if (targetCount == sourceCount && cursor != null && cursor > 0) {
                    yield recoveryMap(currentStepId, "after", cursor, "READY",
                            "回填游标和行数一致；可从边界恢复并执行终扫", observation);
                }
                if (!target || !trigger) {
                    yield blocked(currentStepId, observation, "回填前置物理结构缺失，无法证明状态");
                }
                long firstMissing = observation.get("firstMissingId") == null
                        ? 1L : ((Number) observation.get("firstMissingId")).longValue();
                yield recoveryMap(currentStepId, "batch_after",
                        Math.max(0L, firstMissing - 1), "READY",
                        "批次采用物理事务提交；恢复游标取目标表最早缺失主键的前一个稳定边界", observation);
            }
            case "verify" -> recoveryMap(currentStepId, "before", cursor, "READY",
                    "验证是只读派生物，可重新计算", observation);
            case "cutover" -> {
                boolean switched = Boolean.TRUE.equals(observation.get("archiveExists"))
                        && Boolean.TRUE.equals(observation.get("oldExists"))
                        && !Boolean.TRUE.equals(observation.get("targetExists"))
                        && !Boolean.TRUE.equals(observation.get("triggerExists"));
                yield switched
                        ? recoveryMap(currentStepId, "after", null, "READY",
                                "切换为单一 DDL 事务；观察到终局名称和触发器状态，证明已提交", observation)
                        : (old && target)
                        ? recoveryMap(currentStepId, "before", null, "READY",
                                "旧名、新表仍并存；可重新执行原子切换事务", observation)
                        : blocked(currentStepId, observation, "切换后的名称组合无法证明为原子前态或后态");
            }
            case "final_verify" -> recoveryMap(currentStepId, "before", null, "READY",
                    "终局证明为派生物，可重新计算；结构与数据仍会重新校验", observation);
            default -> blocked(currentStepId, observation, "未知步骤类型，不能凭名称推断恢复动作");
        };
    }

    private Map<String, Object> observe(String schema) {
        try (Connection connection = sandbox.connection(schema)) {
            boolean old = physical.tableExists(connection, schema, "customers");
            boolean target = physical.tableExists(connection, schema, "customers_v2");
            boolean archive = physical.tableExists(connection, schema, "customers_old");
            boolean trigger = physical.triggerExists(connection, schema, "trg_customers_dualwrite");
            long sourceCount = old ? physical.count(connection, schema, "customers")
                    : archive ? physical.count(connection, schema, "customers_old") : 0L;
            long targetCount;
            if (archive && old && !target) {
                targetCount = physical.count(connection, schema, "customers");
            } else if (target) {
                targetCount = physical.count(connection, schema, "customers_v2");
            } else {
                targetCount = 0L;
            }
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("oldExists", old);
            observation.put("targetExists", target);
            observation.put("archiveExists", archive);
            observation.put("triggerExists", trigger);
            observation.put("sourceCount", sourceCount);
            observation.put("targetCount", targetCount);
            observation.put("firstMissingId", target
                    ? physical.firstSourceIdMissingInTarget(connection, schema)
                    : (archive && old ? 0L : 0L));
            return observation;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to observe physical state", e);
        }
    }

    private Map<String, Object> blocked(String stepId, Map<String, Object> observation, String diagnosis) {
        Map<String, Object> result = recoveryMap(stepId, "unknown", null, "BLOCKED", diagnosis, observation);
        result.put("requiresDecision", true);
        return result;
    }

    private Map<String, Object> recoveryMap(String stepId, String phase, Long cursor,
                                            String status, String diagnosis) {
        return recoveryMap(stepId, phase, cursor, status, diagnosis, Map.of());
    }

    private Map<String, Object> recoveryMap(String stepId, String phase, Long cursor,
                                            String status, String diagnosis, Map<String, Object> observation) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stepId", stepId);
        map.put("phase", phase);
        map.put("cursorId", cursor);
        map.put("status", status);
        map.put("diagnosis", diagnosis);
        map.put("physicalObservation", observation);
        map.put("requiresDecision", "BLOCKED".equals(status));
        return map;
    }

    private boolean finalTerminalProof(Map<String, Object> run, JsonNode definition) {
        List<Map<String, Object>> verifications = runs.verifications((String) run.get("id"));
        boolean finalPass = verifications.stream()
                .anyMatch(item -> "final_invariants".equals(item.get("stepId")) && "PASS".equals(item.get("status")));
        boolean hashChain = verifyEventChain((String) run.get("id"));
        Map<String, Object> observation = observe((String) run.get("schemaName"));
        boolean structure = Boolean.TRUE.equals(observation.get("oldExists"))
                && Boolean.TRUE.equals(observation.get("archiveExists"))
                && !Boolean.TRUE.equals(observation.get("targetExists"))
                && !Boolean.TRUE.equals(observation.get("triggerExists"));
        boolean counts = observation.get("sourceCount").equals(observation.get("targetCount"));
        return finalPass && hashChain && structure && counts;
    }

    public boolean verifyEventChain(String runId) {
        String previous = null;
        for (Map<String, Object> event : runs.events(runId)) {
            String expected = Json.sha256((previous == null ? "GENESIS" : previous) + "\n"
                    + Json.canonical(Json.mapper().valueToTree(event.get("payload"))));
            if (!expected.equals(event.get("checksum"))) {
                return false;
            }
            previous = (String) event.get("checksum");
        }
        return true;
    }

    private void appendPhysicalEvidence(String runId, JsonNode step, String outcome, Map<String, Object> observation) {
        runs.appendEvent(runId, "PHYSICAL_EFFECT_OBSERVED", step.path("id").asText(), "after", Map.of(
                "outcome", outcome,
                "physicalObservation", observation,
                "ruleVersion", Json.RULE_VERSION,
                "definitionFingerprint", fingerprint(runId)
        ));
    }

    private JsonNode definition(Map<String, Object> run) {
        return (JsonNode) definitions.byFingerprint((String) run.get("fingerprint")).get("content");
    }

    private String fingerprint(String runId) {
        return (String) runs.getRun(runId).get("fingerprint");
    }

    private JsonNode findStep(JsonNode definition, String stepId) {
        for (JsonNode step : definition.path("steps")) {
            if (stepId.equals(step.path("id").asText())) {
                return step;
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "日志引用了当前定义中不存在的步骤: " + stepId);
    }

    private int stepIndex(JsonNode definition, String stepId) {
        if (stepId == null) {
            return 0;
        }
        for (int i = 0; i < definition.path("steps").size(); i++) {
            if (stepId.equals(definition.path("steps").get(i).path("id").asText())) {
                return i;
            }
        }
        return definition.path("steps").size();
    }

    private String nextStepId(JsonNode definition, int currentIndex) {
        int next = currentIndex + 1;
        return next < definition.path("steps").size()
                ? definition.path("steps").get(next).path("id").asText() : null;
    }

    private void crashPoint(Map<String, Object> run, String point) {
        crash.maybeCrash((String) run.get("failureArm"), (String) run.get("exitMode"), point);
    }

    private List<String> failurePoints(JsonNode definition) {
        java.util.ArrayList<String> points = new java.util.ArrayList<>();
        for (JsonNode step : definition.path("steps")) {
            JsonNode declared = step.get("failurePoints");
            if (declared != null && declared.isArray()) {
                for (JsonNode point : declared) {
                    points.add(point.path("id").asText());
                }
            }
        }
        return points;
    }

    private Map<String, Object> requireRunnable(String runId) {
        Map<String, Object> run = runs.getRun(runId);
        String status = (String) run.get("status");
        if (!List.of("NEVER_STARTED", "READY", "AWAITING_DECISION", "PAUSED").contains(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "运行状态 " + status + " 不能启动或继续");
        }
        return run;
    }
}
