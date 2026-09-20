package local.migrationlab.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.migrationlab.engine.MigrationEngine;
import local.migrationlab.support.ApiException;
import local.migrationlab.support.Json;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class EvidenceService {
    private final RunStore runs;
    private final DefinitionStore definitions;
    private final MigrationEngine engine;
    private final JdbcTemplate jdbc;

    public EvidenceService(RunStore runs, DefinitionStore definitions,
                           MigrationEngine engine, JdbcTemplate jdbc) {
        this.runs = runs;
        this.definitions = definitions;
        this.engine = engine;
        this.jdbc = jdbc;
    }

    public Map<String, Object> exportRun(String runId) {
        Map<String, Object> run = runs.getRun(runId);
        Map<String, Object> definition = definitions.byFingerprint((String) run.get("fingerprint"));
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("bundleVersion", "migration-lab.evidence.v1");
        bundle.put("exportedAt", java.time.Instant.now().toString());
        bundle.put("ruleVersion", Json.RULE_VERSION);
        bundle.put("definition", withoutRawContent(definition));
        Map<String, Object> runEvidence = new LinkedHashMap<>(run);
        runEvidence.put("events", runs.events(runId));
        runEvidence.put("batches", runs.batches(runId));
        runEvidence.put("verifications", runs.verifications(runId));
        runEvidence.put("eventChainValid", engine.verifyEventChain(runId));
        bundle.put("run", runEvidence);
        String raw = Json.write(bundle);
        Map<String, Object> envelope = new LinkedHashMap<>(bundle);
        envelope.put("evidenceSha256", Json.sha256(raw));
        return envelope;
    }

    public Map<String, Object> importBundle(String rawJson) {
        JsonNode bundle = Json.read(rawJson);
        String sha = Json.sha256(rawJson);
        Map<String, Object> analysis = analyze(bundle);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into control.evidence_bundle
                (id, received_sha256, raw_json, kind, analysis_status, analysis,
                 rule_version, source_fingerprint)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, sha, Json.write(bundle), bundle.path("kind").asText("RUN_EVIDENCE"),
                analysis.get("status"), Json.write(analysis),
                bundle.path("ruleVersion").asText(Json.RULE_VERSION),
                bundle.path("run").path("fingerprint").asText(
                        bundle.path("definition").path("fingerprint").asText(null)));
        return getEvidence(id);
    }

    public Map<String, Object> getEvidence(String id) {
        List<Map<String, Object>> rows = jdbc.query("""
                select id, received_sha256, raw_json, kind, analysis_status, analysis,
                       rule_version, source_fingerprint, received_at
                from control.evidence_bundle where id = ?
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getString("id"));
            item.put("receivedSha256", rs.getString("received_sha256"));
            item.put("raw", Json.read(rs.getString("raw_json")));
            item.put("kind", rs.getString("kind"));
            item.put("analysisStatus", rs.getString("analysis_status"));
            item.put("analysis", Json.read(rs.getString("analysis")));
            item.put("ruleVersion", rs.getString("rule_version"));
            item.put("sourceFingerprint", rs.getString("source_fingerprint"));
            item.put("receivedAt", rs.getTimestamp("received_at").toInstant());
            return item;
        }, id);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Evidence bundle not found");
        }
        return rows.get(0);
    }

    public List<Map<String, Object>> listEvidence() {
        return jdbc.query("""
                select id, received_sha256, kind, analysis_status, analysis,
                       rule_version, source_fingerprint, received_at
                from control.evidence_bundle order by received_at desc
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getString("id"));
            item.put("receivedSha256", rs.getString("received_sha256"));
            item.put("kind", rs.getString("kind"));
            item.put("analysisStatus", rs.getString("analysis_status"));
            item.put("analysis", Json.read(rs.getString("analysis")));
            item.put("ruleVersion", rs.getString("rule_version"));
            item.put("sourceFingerprint", rs.getString("source_fingerprint"));
            item.put("receivedAt", rs.getTimestamp("received_at").toInstant());
            return item;
        });
    }

    private Map<String, Object> analyze(JsonNode bundle) {
        JsonNode run = bundle.path("run");
        String expectedRule = bundle.path("ruleVersion").asText();
        boolean ruleMatches = Json.RULE_VERSION.equals(expectedRule);
        String previous = null;
        boolean chain = true;
        for (JsonNode event : run.path("events")) {
            String body = Json.canonical(event.path("payload"));
            String checksum = Json.sha256((previous == null ? "GENESIS" : previous) + "\n" + body);
            chain &= checksum.equals(event.path("checksum").asText());
            previous = event.path("checksum").asText(null);
        }
        boolean terminalProof = run.path("status").asText().equals("COMPLETED")
                || hasPassingVerification(run, "final_invariants");
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("status", ruleMatches && chain && terminalProof ? "PROVEN" : "REJECTED");
        analysis.put("ruleVersionSupported", ruleMatches);
        analysis.put("eventChainValid", chain);
        analysis.put("terminalProofPresent", terminalProof);
        analysis.put("observedRunStatus", run.path("status").asText(null));
        analysis.put("sourceFingerprint", run.path("fingerprint").asText(null));
        return analysis;
    }

    private boolean hasPassingVerification(JsonNode run, String stepId) {
        for (JsonNode verification : run.path("verifications")) {
            if (stepId.equals(verification.path("stepId").asText())
                    && "PASS".equals(verification.path("status").asText())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> withoutRawContent(Map<String, Object> definition) {
        Map<String, Object> copy = new LinkedHashMap<>(definition);
        copy.put("content", copy.get("content"));
        return copy;
    }
}
