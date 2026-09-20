package local.migrationlab.web;

import java.util.Map;
import local.migrationlab.domain.DefinitionStore;
import local.migrationlab.domain.EvidenceService;
import local.migrationlab.domain.RehearsalService;
import local.migrationlab.domain.RunStore;
import local.migrationlab.engine.MigrationEngine;
import local.migrationlab.support.ApiException;
import local.migrationlab.support.Json;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final DefinitionStore definitions;
    private final MigrationEngine engine;
    private final RunStore runs;
    private final EvidenceService evidence;
    private final RehearsalService rehearsal;

    public ApiController(DefinitionStore definitions, MigrationEngine engine, RunStore runs,
                         EvidenceService evidence, RehearsalService rehearsal) {
        this.definitions = definitions;
        this.engine = engine;
        this.runs = runs;
        this.evidence = evidence;
        this.rehearsal = rehearsal;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "ruleVersion", Json.RULE_VERSION);
    }

    @GetMapping("/definitions")
    public Object listDefinitions() {
        return definitions.definitions();
    }

    @GetMapping("/definitions/{id}")
    public Map<String, Object> definition(@PathVariable String id) {
        return definitions.latest(id);
    }

    @GetMapping("/definitions/{id}/versions")
    public Object versions(@PathVariable String id) {
        return definitions.versions(id);
    }

    @GetMapping("/definitions/by-fingerprint/{fingerprint}")
    public Map<String, Object> byFingerprint(@PathVariable String fingerprint) {
        return definitions.byFingerprint(fingerprint);
    }

    @PostMapping("/definitions")
    public Map<String, Object> createDefinition(@RequestBody String body) {
        return definitions.saveInitial(body);
    }

    @PostMapping("/definitions/{id}/branches")
    public Map<String, Object> branch(@PathVariable String id,
                                      @RequestParam String parentFingerprint,
                                      @RequestBody String body) {
        return definitions.saveBranch(id, parentFingerprint, body);
    }

    @PostMapping("/runs")
    public Map<String, Object> createRun(@RequestBody(required = false) Map<String, Object> request) {
        if (request == null || request.get("fingerprint") == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "fingerprint is required");
        }
        return engine.createRun(String.valueOf(request.get("fingerprint")),
                request.get("name") == null ? null : String.valueOf(request.get("name")),
                request.get("exitMode") == null ? "simulate" : String.valueOf(request.get("exitMode")));
    }

    @GetMapping("/runs")
    public Object runs() {
        return runs.listRuns();
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> run(@PathVariable String id) {
        return engine.detail(id);
    }

    @PostMapping("/runs/{id}/start")
    public Map<String, Object> start(@PathVariable String id, @RequestBody(required = false) Map<String, Object> request) {
        String fault = request == null || request.get("faultPoint") == null
                ? null : String.valueOf(request.get("faultPoint"));
        String mode = request == null || request.get("exitMode") == null
                ? "simulate" : String.valueOf(request.get("exitMode"));
        return engine.start(id, fault, mode);
    }

    @PostMapping("/runs/{id}/arm")
    public Map<String, Object> arm(@PathVariable String id, @RequestBody Map<String, Object> request) {
        return engine.arm(id, String.valueOf(request.get("faultPoint")),
                String.valueOf(request.getOrDefault("exitMode", "simulate")));
    }

    @PostMapping("/runs/{id}/resume")
    public Map<String, Object> resume(@PathVariable String id) {
        return engine.resume(id);
    }

    @PostMapping("/runs/{id}/rollback")
    public Map<String, Object> rollback(@PathVariable String id) {
        return engine.rollback(id);
    }

    @PostMapping("/runs/{id}/recover")
    public Map<String, Object> recover(@PathVariable String id) {
        engine.recoverAtStartup();
        return engine.detail(id);
    }

    @PostMapping("/runs/{id}/test-insert")
    public Map<String, Object> testInsert(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, String> request) {
        return engine.testInsert(id, request == null ? null : request.get("email"));
    }

    @GetMapping("/runs/{id}/evidence")
    public Map<String, Object> exportEvidence(@PathVariable String id) {
        return evidence.exportRun(id);
    }

    @PostMapping("/evidence")
    public Map<String, Object> importEvidence(@RequestBody String rawJson) {
        return evidence.importBundle(rawJson);
    }

    @GetMapping("/evidence")
    public Object evidenceList() {
        return evidence.listEvidence();
    }

    @GetMapping("/evidence/{id}")
    public Map<String, Object> evidence(@PathVariable String id) {
        return evidence.getEvidence(id);
    }

    @PostMapping("/rehearsals")
    public Map<String, Object> rehearsal(@RequestBody(required = false) Map<String, String> request) {
        if (request == null || request.get("fingerprint") == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "fingerprint is required");
        }
        return rehearsal.runSuite(request.get("fingerprint"));
    }
}
