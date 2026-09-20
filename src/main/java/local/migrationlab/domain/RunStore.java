package local.migrationlab.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import local.migrationlab.support.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Component
public class RunStore {
    private final JdbcTemplate jdbc;

    public RunStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> createRun(Map<String, Object> fields) {
        jdbc.update("""
                insert into control.migration_run
                (id, definition_id, fingerprint, rule_version, name, schema_name, status,
                 current_step, cursor_id, diagnosis, base_snapshot_fingerprint)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, fields.get("id"), fields.get("definitionId"), fields.get("fingerprint"),
                fields.get("ruleVersion"), fields.get("name"), fields.get("schemaName"),
                fields.getOrDefault("status", "NEVER_STARTED"), fields.get("currentStep"),
                fields.get("cursorId"), fields.get("diagnosis"),
                fields.get("baseSnapshotFingerprint"));
        return getRun((String) fields.get("id"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> appendEvent(String runId, String type, String stepId, String phase, Map<String, Object> payload) {
        Integer lastSeq = jdbc.queryForObject(
                "select coalesce(max(seq), 0) from control.run_event where run_id = ?",
                Integer.class, runId);
        int seq = (lastSeq == null ? 0 : lastSeq) + 1;
        List<String> previousRows = jdbc.queryForList(
                "select checksum from control.run_event where run_id = ? order by seq desc fetch first 1 row only",
                String.class, runId);
        String previous = previousRows.isEmpty() ? null : previousRows.get(0);
        String body = eventEnvelope(runId, seq, type, stepId, phase, payload == null ? Map.of() : payload);
        String checksum = Json.sha256((previous == null ? "GENESIS" : previous) + "\n" + body);
        jdbc.update("""
                insert into control.run_event
                (run_id, seq, event_type, step_id, phase, previous_checksum, checksum, payload)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, seq, type, stepId, phase, previous, checksum, body);
        return Map.of("seq", seq, "checksum", checksum, "previousChecksum", previous == null ? "GENESIS" : previous);
    }

    public static String eventEnvelope(String runId, int seq, String type, String stepId,
                                       String phase, Object payload) {
        return Json.canonical(Json.read(Json.write(Map.of(
                "runId", runId,
                "seq", seq,
                "type", type,
                "stepId", stepId == null ? "" : stepId,
                "phase", phase == null ? "" : phase,
                "payload", payload == null ? Map.of() : payload
        ))));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateState(String runId, String status, String stepId, Long cursorId, String diagnosis) {
        jdbc.update("""
                update control.migration_run
                set status = ?, current_step = ?, cursor_id = ?, diagnosis = ?, updated_at = ?
                where id = ?
                """, status, stepId, cursorId, diagnosis, Timestamp.from(Instant.now()), runId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String runId) {
        jdbc.update("""
                update control.migration_run
                set status = 'COMPLETED', current_step = null, diagnosis = null,
                    updated_at = ?, completed_at = ?
                where id = ?
                """, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), runId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearFailureArm(String runId) {
        jdbc.update("update control.migration_run set failure_arm = null, updated_at = ? where id = ?",
                Timestamp.from(Instant.now()), runId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void armFailure(String runId, String faultPoint, String exitMode) {
        jdbc.update("update control.migration_run set failure_arm = ?, exit_mode = ?, updated_at = ? where id = ?",
                faultPoint, exitMode, Timestamp.from(Instant.now()), runId);
    }

    public List<Map<String, Object>> listRuns() {
        return jdbc.query("select * from control.migration_run order by started_at desc, id", this::runRow);
    }

    public Map<String, Object> getRun(String runId) {
        List<Map<String, Object>> rows = jdbc.query(
                "select * from control.migration_run where id = ?", this::runRow, runId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Run not found: " + runId);
        }
        return rows.get(0);
    }

    public List<Map<String, Object>> events(String runId) {
        return jdbc.query("""
                select id, seq, event_type, step_id, phase, previous_checksum, checksum, payload, created_at
                from control.run_event where run_id = ? order by seq
                """, (rs, row) -> {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", rs.getLong("id"));
            event.put("seq", rs.getInt("seq"));
            event.put("type", rs.getString("event_type"));
            event.put("stepId", rs.getString("step_id"));
            event.put("phase", rs.getString("phase"));
            event.put("previousChecksum", rs.getString("previous_checksum"));
            event.put("checksum", rs.getString("checksum"));
            event.put("payload", Json.read(rs.getString("payload")));
            event.put("createdAt", rs.getTimestamp("created_at").toInstant());
            return event;
        }, runId);
    }

    public List<Map<String, Object>> batches(String runId) {
        return jdbc.query("""
                select step_id, batch_no, last_id, source_rows, inserted_rows, checksum, state, recovered
                from control.batch_record where run_id = ? order by step_id, batch_no
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stepId", rs.getString("step_id"));
            item.put("batchNo", rs.getInt("batch_no"));
            item.put("lastId", rs.getLong("last_id"));
            item.put("sourceRows", rs.getInt("source_rows"));
            item.put("insertedRows", rs.getInt("inserted_rows"));
            item.put("checksum", rs.getString("checksum"));
            item.put("state", rs.getString("state"));
            item.put("recovered", rs.getBoolean("recovered"));
            return item;
        }, runId);
    }

    public List<Map<String, Object>> verifications(String runId) {
        return jdbc.query("""
                select step_id, verification_type, status, summary, detail, rule_version, source_fingerprint
                from control.verification_record where run_id = ? order by id
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stepId", rs.getString("step_id"));
            item.put("type", rs.getString("verification_type"));
            item.put("status", rs.getString("status"));
            item.put("summary", rs.getString("summary"));
            item.put("detail", Json.read(rs.getString("detail")));
            item.put("ruleVersion", rs.getString("rule_version"));
            item.put("sourceFingerprint", rs.getString("source_fingerprint"));
            return item;
        }, runId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveBatch(String runId, String stepId, int batchNo, long lastId,
                          int sourceRows, int insertedRows, String checksum,
                          String state, boolean recovered) {
        jdbc.update("""
                merge into control.batch_record
                (run_id, step_id, batch_no, last_id, source_rows, inserted_rows, checksum, state, recovered)
                key (run_id, step_id, batch_no)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, stepId, batchNo, lastId, sourceRows, insertedRows, checksum, state, recovered);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveVerification(String runId, String stepId, String type, boolean passed,
                                 String summary, Object detail, String sourceFingerprint) {
        jdbc.update("""
                insert into control.verification_record
                (run_id, step_id, verification_type, status, summary, detail, rule_version, source_fingerprint)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, stepId, type, passed ? "PASS" : "FAIL", summary, Json.write(detail),
                Json.RULE_VERSION, sourceFingerprint);
    }

    private Object value(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> runRow(ResultSet rs, int row) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", rs.getString("id"));
        item.put("definitionId", rs.getString("definition_id"));
        item.put("fingerprint", rs.getString("fingerprint"));
        item.put("ruleVersion", rs.getString("rule_version"));
        item.put("name", rs.getString("name"));
        item.put("schemaName", rs.getString("schema_name"));
        item.put("status", rs.getString("status"));
        item.put("failureArm", rs.getString("failure_arm"));
        item.put("exitMode", rs.getString("exit_mode"));
        item.put("currentStep", rs.getString("current_step"));
        long cursor = rs.getLong("cursor_id");
        item.put("cursorId", rs.wasNull() ? null : cursor);
        item.put("diagnosis", rs.getString("diagnosis"));
        item.put("baseSnapshotFingerprint", rs.getString("base_snapshot_fingerprint"));
        Timestamp started = rs.getTimestamp("started_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        Timestamp completed = rs.getTimestamp("completed_at");
        item.put("startedAt", started == null ? null : started.toInstant());
        item.put("updatedAt", updated == null ? null : updated.toInstant());
        item.put("completedAt", completed == null ? null : completed.toInstant());
        return item;
    }
}
