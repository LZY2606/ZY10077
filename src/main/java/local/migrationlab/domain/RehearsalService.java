package local.migrationlab.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.migrationlab.engine.MigrationEngine;
import local.migrationlab.support.Json;
import org.springframework.stereotype.Service;

@Service
public class RehearsalService {
    private final DefinitionStore definitions;
    private final MigrationEngine engine;
    private final RunStore runs;

    public RehearsalService(DefinitionStore definitions, MigrationEngine engine, RunStore runs) {
        this.definitions = definitions;
        this.engine = engine;
        this.runs = runs;
    }

    public Map<String, Object> runSuite(String fingerprint) {
        Map<String, Object> definitionView = definitions.byFingerprint(fingerprint);
        JsonNode definition = (JsonNode) definitionView.get("content");
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode step : definition.path("steps")) {
            for (JsonNode point : step.path("failurePoints")) {
                String faultPoint = point.path("id").asText();
                results.add(rehearse(fingerprint, faultPoint, "resume",
                        point.path("expected").asText("从中间态安全继续")));
                results.add(rehearse(fingerprint, faultPoint, "rollback",
                        "按逆序补偿并回到 ROLLED_BACK"));
            }
        }
        boolean passed = results.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")));
        Map<String, Object> suite = new LinkedHashMap<>();
        suite.put("id", UUID.randomUUID().toString());
        suite.put("definitionId", definitionView.get("definitionId"));
        suite.put("fingerprint", fingerprint);
        suite.put("ruleVersion", Json.RULE_VERSION);
        suite.put("status", passed ? "PASS" : "FAIL");
        suite.put("results", results);
        return suite;
    }

    private Map<String, Object> rehearse(String fingerprint, String faultPoint,
                                         String decision, String expectation) {
        Map<String, Object> created = engine.createRun(fingerprint,
                "演练 " + faultPoint + " / " + decision, "simulate");
        String runId = (String) created.get("id");
        engine.start(runId, faultPoint, "simulate");
        Map<String, Object> afterCrash = runs.getRun(runId);
        boolean safeToDecide = List.of("READY", "AWAITING_DECISION", "PAUSED",
                "RUNNING", "NEVER_STARTED").contains(afterCrash.get("status"));
        engine.recoverAtStartup();
        Map<String, Object> recoveredRun = runs.getRun(runId);
        Map<String, Object> afterDecision;
        if ("BLOCKED".equals(recoveredRun.get("status"))) {
            afterDecision = recoveredRun;
        } else if ("resume".equals(decision)) {
            afterDecision = "NEVER_STARTED".equals(recoveredRun.get("status"))
                    ? engine.start(runId, null, "simulate")
                    : engine.resume(runId);
        } else {
            afterDecision = engine.rollback(runId);
        }
        String expectedStatus = "resume".equals(decision) ? "COMPLETED" : "ROLLED_BACK";
        boolean passed = safeToDecide && !"BLOCKED".equals(afterDecision.get("status"))
                && expectedStatus.equals(afterDecision.get("status"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("faultPoint", faultPoint);
        result.put("decision", decision);
        result.put("expected", expectation);
        result.put("stateAfterCrash", afterCrash.get("status"));
        result.put("diagnosis", afterCrash.get("diagnosis"));
        result.put("stateAfterDecision", afterDecision.get("status"));
        result.put("passed", passed);
        return result;
    }
}
