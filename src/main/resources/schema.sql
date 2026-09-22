CREATE TABLE IF NOT EXISTS def_family (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  family_id CHAR(12) NOT NULL UNIQUE,
  name VARCHAR(200) NOT NULL,
  head_fingerprint CHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS def_revision (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  family_id CHAR(12) NOT NULL,
  fingerprint CHAR(64) NOT NULL UNIQUE,
  parent_fingerprint CHAR(64),
  spec_json CLOB NOT NULL,
  created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_defrev_family ON def_revision(family_id);

CREATE TABLE IF NOT EXISTS mig_run (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_uid CHAR(16) NOT NULL UNIQUE,
  family_id CHAR(12) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  sandbox_schema VARCHAR(40) NOT NULL,
  status VARCHAR(30) NOT NULL,
  armed_fault VARCHAR(200),
  decision VARCHAR(30),
  decision_note CLOB,
  terminal_event VARCHAR(40),
  error CLOB,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_run_fp ON mig_run(fingerprint);

CREATE TABLE IF NOT EXISTS step_inst (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  step_id VARCHAR(100) NOT NULL,
  step_type VARCHAR(30) NOT NULL,
  seq_no INT NOT NULL,
  status VARCHAR(30) NOT NULL,
  cursor_value VARCHAR(100),
  detail CLOB,
  UNIQUE(run_id, step_id)
);

CREATE TABLE IF NOT EXISTS journal (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_uid CHAR(16) NOT NULL,
  step_id VARCHAR(100),
  event VARCHAR(50) NOT NULL,
  payload CLOB,
  created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_journal_run ON journal(run_uid, id);

CREATE TABLE IF NOT EXISTS batch_rec (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_uid CHAR(16) NOT NULL,
  step_id VARCHAR(100) NOT NULL,
  batch_no INT NOT NULL,
  pk_from VARCHAR(100),
  pk_to VARCHAR(100),
  row_count INT NOT NULL,
  checksum VARCHAR(100),
  created_at TIMESTAMP NOT NULL,
  UNIQUE(run_uid, step_id, batch_no)
);

CREATE TABLE IF NOT EXISTS read_check (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_uid CHAR(16) NOT NULL,
  label VARCHAR(100) NOT NULL,
  old_path CLOB,
  new_path CLOB,
  matched BOOLEAN NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS inv_result (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_uid CHAR(16) NOT NULL,
  invariant_id VARCHAR(100) NOT NULL,
  passed BOOLEAN NOT NULL,
  detail CLOB,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS window_write (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_uid CHAR(16) NOT NULL,
  step_id VARCHAR(100) NOT NULL,
  pk_value VARCHAR(100),
  payload CLOB NOT NULL,
  applied_to VARCHAR(20) NOT NULL,
  created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ww_run ON window_write(run_uid, id);

CREATE TABLE IF NOT EXISTS evidence_receipt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  fingerprint CHAR(64) NOT NULL UNIQUE,
  rule_version VARCHAR(40) NOT NULL,
  raw_json CLOB NOT NULL,
  entry_hash CHAR(64) NOT NULL,
  prev_hash CHAR(64),
  received_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS evidence_report (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_fingerprint CHAR(64) NOT NULL,
  rule_version VARCHAR(40) NOT NULL,
  passed BOOLEAN NOT NULL,
  report_json CLOB NOT NULL,
  created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_evrep_src ON evidence_report(source_fingerprint);

CREATE TRIGGER IF NOT EXISTS trg_evidence_no_update
BEFORE UPDATE ON evidence_receipt
FOR EACH ROW CALL "com.example.migsb.engine.ImmutableEvidenceTrigger";
CREATE TRIGGER IF NOT EXISTS trg_evidence_no_delete
BEFORE DELETE ON evidence_receipt
FOR EACH ROW CALL "com.example.migsb.engine.ImmutableEvidenceTrigger";
