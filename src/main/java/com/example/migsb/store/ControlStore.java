package com.example.migsb.store;

import com.example.migsb.domain.States;
import com.example.migsb.store.Rows.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ControlStore {

    private final JdbcTemplate jdbc;

    public ControlStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private static final RowMapper<Family> FAMILY = (rs, n) -> new Family(
            rs.getString("family_id"), rs.getString("name"),
            rs.getString("head_fingerprint"), rs.getTimestamp("created_at"));

    private static final RowMapper<Revision> REVISION = (rs, n) -> new Revision(
            rs.getString("family_id"), rs.getString("fingerprint"),
            rs.getString("parent_fingerprint"), rs.getString("spec_json"),
            rs.getTimestamp("created_at"));

    private static final RowMapper<Run> RUN = (rs, n) -> new Run(
            rs.getLong("id"), rs.getString("run_uid"), rs.getString("family_id"),
            rs.getString("fingerprint"), rs.getString("sandbox_schema"),
            rs.getString("status"), rs.getString("armed_fault"),
            rs.getString("decision"), rs.getString("decision_note"),
            rs.getString("terminal_event"), rs.getString("error"),
            rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"));

    private static final RowMapper<Step> STEP = (rs, n) -> new Step(
            rs.getLong("id"), rs.getLong("run_id"), rs.getString("step_id"),
            rs.getString("step_type"), rs.getInt("seq_no"), rs.getString("status"),
            rs.getString("cursor_value"), rs.getString("detail"));

    private static final RowMapper<Journal> JOURNAL = (rs, n) -> new Journal(
            rs.getLong("id"), rs.getString("run_uid"), rs.getString("step_id"),
            rs.getString("event"), rs.getString("payload"), rs.getTimestamp("created_at"));

    private static final RowMapper<Batch> BATCH = (rs, n) -> new Batch(
            rs.getLong("id"), rs.getString("run_uid"), rs.getString("step_id"),
            rs.getInt("batch_no"), rs.getString("pk_from"), rs.getString("pk_to"),
            rs.getInt("row_count"), rs.getString("checksum"), rs.getTimestamp("created_at"));

    private static final RowMapper<ReadCheck> READ_CHECK = (rs, n) -> new ReadCheck(
            rs.getLong("id"), rs.getString("run_uid"), rs.getString("label"),
            rs.getString("old_path"), rs.getString("new_path"),
            rs.getBoolean("matched"), rs.getTimestamp("created_at"));

    private static final RowMapper<InvResult> INV = (rs, n) -> new InvResult(
            rs.getLong("id"), rs.getString("run_uid"), rs.getString("invariant_id"),
            rs.getBoolean("passed"), rs.getString("detail"), rs.getTimestamp("created_at"));

    private static final RowMapper<WindowWrite> WW = (rs, n) -> new WindowWrite(
            rs.getLong("id"), rs.getString("run_uid"), rs.getString("step_id"),
            rs.getString("pk_value"), rs.getString("payload"),
            rs.getString("applied_to"), rs.getTimestamp("created_at"));

    private static final RowMapper<EvidenceReceipt> EVIDENCE = (rs, n) -> new EvidenceReceipt(
            rs.getLong("id"), rs.getString("fingerprint"), rs.getString("rule_version"),
            rs.getString("raw_json"), rs.getString("entry_hash"),
            rs.getString("prev_hash"), rs.getTimestamp("received_at"));

    private static final RowMapper<EvidenceReport> REPORT = (rs, n) -> new EvidenceReport(
            rs.getLong("id"), rs.getString("source_fingerprint"),
            rs.getString("rule_version"), rs.getBoolean("passed"),
            rs.getString("report_json"), rs.getTimestamp("created_at"));

    // ---------- definitions ----------

    public Optional<Family> findFamily(String familyId) {
        return jdbc.query("SELECT * FROM def_family WHERE family_id=?", FAMILY, familyId).stream().findFirst();
    }

    public List<Family> listFamilies() {
        return jdbc.query("SELECT * FROM def_family ORDER BY id", FAMILY);
    }

    public void insertFamily(String familyId, String name, String headFingerprint) {
        jdbc.update("INSERT INTO def_family(family_id,name,head_fingerprint,created_at) VALUES(?,?,?,?)",
                familyId, name, headFingerprint, now());
    }

    public void updateFamilyHead(String familyId, String headFingerprint) {
        jdbc.update("UPDATE def_family SET head_fingerprint=? WHERE family_id=?", headFingerprint, familyId);
    }

    public Optional<Revision> findRevision(String fingerprint) {
        return jdbc.query("SELECT * FROM def_revision WHERE fingerprint=?", REVISION, fingerprint)
                .stream().findFirst();
    }

    public List<Revision> listRevisions(String familyId) {
        return jdbc.query("SELECT * FROM def_revision WHERE family_id=? ORDER BY id", REVISION, familyId);
    }

    public void insertRevision(String familyId, String fingerprint, String parent, String specJson) {
        jdbc.update("INSERT INTO def_revision(family_id,fingerprint,parent_fingerprint,spec_json,created_at) "
                + "VALUES(?,?,?,?,?)", familyId, fingerprint, parent, specJson, now());
    }

    // ---------- runs / steps ----------

    public long insertRun(String runUid, String familyId, String fingerprint,
                          String sandboxSchema, String armedFault) {
        Timestamp ts = now();
        jdbc.update("INSERT INTO mig_run(run_uid,family_id,fingerprint,sandbox_schema,status,armed_fault,"
                + "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                runUid, familyId, fingerprint, sandboxSchema, States.RUN_CREATED,
                armedFault == null ? null : armedFault, ts, ts);
        return jdbc.queryForObject("SELECT id FROM mig_run WHERE run_uid=?", Long.class, runUid);
    }

    public void updateRunArmedFault(String runUid, String armedFault) {
        jdbc.update("UPDATE mig_run SET armed_fault=?, updated_at=? WHERE run_uid=?",
                armedFault, now(), runUid);
    }

    public void updateRunStatus(String runUid, String status, String error) {
        jdbc.update("UPDATE mig_run SET status=?, error=COALESCE(?,error), updated_at=? WHERE run_uid=?",
                status, error, now(), runUid);
    }

    public void updateRunDecision(String runUid, String decision, String note, String status) {
        jdbc.update("UPDATE mig_run SET decision=?, decision_note=COALESCE(decision_note,'')||?, status=?, "
                + "updated_at=? WHERE run_uid=?", decision, "\n[" + now() + "] " + note, status, now(), runUid);
    }

    public void updateRunTerminalEvent(String runUid, String terminalEvent, String status) {
        jdbc.update("UPDATE mig_run SET terminal_event=?, status=?, updated_at=? WHERE run_uid=?",
                terminalEvent, status, now(), runUid);
    }

    public Optional<Run> findRunByUid(String runUid) {
        return jdbc.query("SELECT * FROM mig_run WHERE run_uid=?", RUN, runUid).stream().findFirst();
    }

    public List<Run> listRuns() {
        return jdbc.query("SELECT * FROM mig_run ORDER BY id DESC", RUN);
    }

    public List<Run> listRunsByFingerprint(String fingerprint) {
        return jdbc.query("SELECT * FROM mig_run WHERE fingerprint=? ORDER BY id", RUN, fingerprint);
    }

    public List<Run> listRunsByStatus(String status) {
        return jdbc.query("SELECT * FROM mig_run WHERE status=?", RUN, status);
    }

    public List<Run> listRunsByFamily(String familyId) {
        return jdbc.query("SELECT * FROM mig_run WHERE family_id=? ORDER BY id", RUN, familyId);
    }

    public void insertStep(long runId, String stepId, String stepType, int seqNo, String status) {
        jdbc.update("INSERT INTO step_inst(run_id,step_id,step_type,seq_no,status) VALUES(?,?,?,?,?)",
                runId, stepId, stepType, seqNo, status);
    }

    public List<Step> listSteps(long runId) {
        return jdbc.query("SELECT * FROM step_inst WHERE run_id=? ORDER BY seq_no, id", STEP, runId);
    }

    public Optional<Step> findStep(long runId, String stepId) {
        return jdbc.query("SELECT * FROM step_inst WHERE run_id=? AND step_id=?", STEP, runId, stepId)
                .stream().findFirst();
    }

    public void updateStepStatus(long runId, String stepId, String status) {
        jdbc.update("UPDATE step_inst SET status=? WHERE run_id=? AND step_id=?", status, runId, stepId);
    }

    public void updateStepCursor(long runId, String stepId, String cursor) {
        jdbc.update("UPDATE step_inst SET cursor_value=? WHERE run_id=? AND step_id=?", cursor, runId, stepId);
    }

    public void updateStepDetail(long runId, String stepId, String detail) {
        jdbc.update("UPDATE step_inst SET detail=? WHERE run_id=? AND step_id=?", detail, runId, stepId);
    }

    // ---------- journal ----------

    public void appendJournal(String runUid, String stepId, String event, String payload) {
        jdbc.update("INSERT INTO journal(run_uid,step_id,event,payload,created_at) VALUES(?,?,?,?,?)",
                runUid, stepId, event, payload, now());
    }

    public List<Journal> listJournal(String runUid) {
        return jdbc.query("SELECT * FROM journal WHERE run_uid=? ORDER BY id", JOURNAL, runUid);
    }

    public List<Journal> listJournalEvent(String runUid, String event) {
        return jdbc.query("SELECT * FROM journal WHERE run_uid=? AND event=? ORDER BY id",
                JOURNAL, runUid, event);
    }

    // ---------- batches ----------

    public void insertBatch(String runUid, String stepId, int batchNo, String pkFrom, String pkTo,
                            int rowCount, String checksum) {
        jdbc.update("INSERT INTO batch_rec(run_uid,step_id,batch_no,pk_from,pk_to,row_count,checksum,created_at) "
                + "VALUES(?,?,?,?,?,?,?,?)", runUid, stepId, batchNo, pkFrom, pkTo, rowCount, checksum, now());
    }

    public List<Batch> listBatches(String runUid, String stepId) {
        return jdbc.query("SELECT * FROM batch_rec WHERE run_uid=? AND step_id=? ORDER BY batch_no",
                BATCH, runUid, stepId);
    }

    // ---------- read checks / invariants ----------

    public void insertReadCheck(String runUid, String label, String oldPath, String newPath, boolean matched) {
        jdbc.update("INSERT INTO read_check(run_uid,label,old_path,new_path,matched,created_at) "
                + "VALUES(?,?,?,?,?,?)", runUid, label, oldPath, newPath, matched, now());
    }

    public List<ReadCheck> listReadChecks(String runUid) {
        return jdbc.query("SELECT * FROM read_check WHERE run_uid=? ORDER BY id", READ_CHECK, runUid);
    }

    public void insertInvResult(String runUid, String invariantId, boolean passed, String detail) {
        jdbc.update("INSERT INTO inv_result(run_uid,invariant_id,passed,detail,created_at) VALUES(?,?,?,?,?)",
                runUid, invariantId, passed, detail, now());
    }

    public List<InvResult> listInvResults(String runUid) {
        return jdbc.query("SELECT * FROM inv_result WHERE run_uid=? ORDER BY id", INV, runUid);
    }

    // ---------- window writes ----------

    public long insertWindowWrite(String runUid, String stepId, String pkValue,
                                  String payload, String appliedTo) {
        jdbc.update("INSERT INTO window_write(run_uid,step_id,pk_value,payload,applied_to,created_at) "
                + "VALUES(?,?,?,?,?,?)", runUid, stepId, pkValue, payload, appliedTo, now());
        return jdbc.queryForObject("SELECT id FROM window_write WHERE run_uid=? ORDER BY id DESC "
                + "FETCH FIRST 1 ROW ONLY", Long.class, runUid);
    }

    public List<WindowWrite> listWindowWrites(String runUid, String stepId) {
        return jdbc.query("SELECT * FROM window_write WHERE run_uid=? AND step_id=? ORDER BY id",
                WW, runUid, stepId);
    }

    // ---------- evidence ----------

    public Optional<EvidenceReceipt> findEvidence(String fingerprint) {
        return jdbc.query("SELECT * FROM evidence_receipt WHERE fingerprint=?", EVIDENCE, fingerprint)
                .stream().findFirst();
    }

    public List<EvidenceReceipt> listEvidence() {
        return jdbc.query("SELECT * FROM evidence_receipt ORDER BY id", EVIDENCE);
    }

    public void insertEvidence(String fingerprint, String ruleVersion, String rawJson,
                               String entryHash, String prevHash) {
        jdbc.update("INSERT INTO evidence_receipt(fingerprint,rule_version,raw_json,entry_hash,prev_hash,"
                + "received_at) VALUES(?,?,?,?,?,?)",
                fingerprint, ruleVersion, rawJson, entryHash, prevHash, now());
    }

    public void insertReport(String sourceFingerprint, String ruleVersion, boolean passed, String reportJson) {
        jdbc.update("INSERT INTO evidence_report(source_fingerprint,rule_version,passed,report_json,created_at) "
                + "VALUES(?,?,?,?,?)", sourceFingerprint, ruleVersion, passed, reportJson, now());
    }

    public List<EvidenceReport> listReports() {
        return jdbc.query("SELECT * FROM evidence_report ORDER BY id DESC", REPORT);
    }

    public List<EvidenceReport> listReportsBySource(String fingerprint) {
        return jdbc.query("SELECT * FROM evidence_report WHERE source_fingerprint=? ORDER BY id DESC",
                REPORT, fingerprint);
    }
}
