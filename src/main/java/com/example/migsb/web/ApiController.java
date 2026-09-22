package com.example.migsb.web;

import com.example.migsb.domain.States;
import com.example.migsb.engine.MigrationEngine;
import com.example.migsb.engine.RecoveryScanner;
import com.example.migsb.store.ControlStore;
import com.example.migsb.store.Rows.Run;
import com.example.migsb.svc.DefinitionService;
import com.example.migsb.svc.EvidenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final DefinitionService definitions;
    private final MigrationEngine engine;
    private final RecoveryScanner scanner;
    private final EvidenceService evidence;
    private final ControlStore store;
    private final ObjectMapper mapper;

    public ApiController(DefinitionService definitions, MigrationEngine engine,
                         RecoveryScanner scanner, EvidenceService evidence,
                         ControlStore store, ObjectMapper mapper) {
        this.definitions = definitions;
        this.engine = engine;
        this.scanner = scanner;
        this.evidence = evidence;
        this.store = store;
        this.mapper = mapper;
    }

    // ---------- definitions ----------

    @GetMapping("/families")
    public List<Map<String, Object>> families() {
        return store.listFamilies().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("familyId", f.familyId());
            m.put("name", f.name());
            m.put("headFingerprint", f.headFingerprint());
            m.put("revisionCount", store.listRevisions(f.familyId()).size());
            return m;
        }).toList();
    }

    @GetMapping("/families/{familyId}")
    public Map<String, Object> family(@PathVariable String familyId) {
        var f = store.findFamily(familyId).orElseThrow(() -> new NotFoundException("家族不存在"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("familyId", f.familyId());
        out.put("name", f.name());
        out.put("headFingerprint", f.headFingerprint());
        out.put("revisions", store.listRevisions(familyId).stream().map(definitions::revisionView).toList());
        return out;
    }

    @GetMapping("/definitions/{fingerprint}")
    public Map<String, Object> definition(@PathVariable String fingerprint) {
        var r = store.findRevision(fingerprint).orElseThrow(() -> new NotFoundException("定义版本不存在"));
        return definitions.revisionView(r);
    }

    public record SubmitRequest(String specJson, String familyId, String baseFingerprint, Boolean forceBranch) {}

    @PostMapping("/definitions")
    public Map<String, Object> submit(@RequestBody SubmitRequest req) {
        boolean force = Boolean.TRUE.equals(req.forceBranch());
        var result = definitions.submit(req.specJson(), req.familyId(), req.baseFingerprint(), force);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("familyId", result.familyId());
        out.put("fingerprint", result.fingerprint());
        out.put("parentFingerprint", result.parentFingerprint());
        out.put("created", result.created());
        out.put("branch", result.branched());
        if (result.branched()) {
            out.put("message", "内容与家族 head 不同，已创建分支版本，未移动 head；已有运行继续绑定旧指纹");
        }
        return out;
    }

    // ---------- runs ----------

    public record CreateRunRequest(String fingerprint, String armedFault) {}

    @PostMapping("/runs")
    public Map<String, Object> createRun(@RequestBody CreateRunRequest req) {
        Run run = engine.createRun(req.fingerprint(), req.armedFault());
        return runView(run.runUid());
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs() {
        return store.listRuns().stream().map(r -> runSummary(r.runUid())).toList();
    }

    @PostMapping("/runs/{uid}/advance")
    public Map<String, Object> advance(@PathVariable String uid) {
        engine.advance(uid);
        return runView(uid);
    }

    @PostMapping("/runs/{uid}/rollback")
    public Map<String, Object> rollback(@PathVariable String uid) {
        engine.rollback(uid);
        return runView(uid);
    }

    public record DecisionRequest(String decision, String note) {}

    @PostMapping("/runs/{uid}/decision")
    public Map<String, Object> decision(@PathVariable String uid, @RequestBody DecisionRequest req) {
        engine.decide(uid, req.decision(), req.note());
        return runView(uid);
    }

    @PostMapping("/recovery/scan")
    public Map<String, Object> scan() {
        var report = scanner.scanAll(true);
        return Map.of("verdicts", report.verdicts(), "halted", report.halted());
    }

    public record WindowWriteRequest(String applyTo, Map<String, Object> row) {}

    @PostMapping("/runs/{uid}/steps/{stepId}/window-write")
    public Map<String, Object> windowWrite(@PathVariable String uid, @PathVariable String stepId,
                                           @RequestBody WindowWriteRequest req) {
        return engine.windowWrite(uid, stepId, req.applyTo(), req.row());
    }

    @PostMapping("/runs/{uid}/steps/{stepId}/close-window")
    public Map<String, Object> closeWindow(@PathVariable String uid, @PathVariable String stepId) {
        engine.closeWindow(uid, stepId);
        return runView(uid);
    }

    // ---------- evidence ----------

    @GetMapping(value = "/evidence/export/{fingerprint}", produces = MediaType.APPLICATION_JSON_VALUE)
    public String exportEvidence(@PathVariable String fingerprint) {
        return evidence.exportBundle(fingerprint);
    }

    @PostMapping("/evidence/import")
    public Map<String, Object> importEvidence(@RequestBody String rawJson) {
        return evidence.importBundle(rawJson);
    }

    @GetMapping("/evidence")
    public Map<String, Object> listEvidence() {
        return Map.of("receipts", evidence.listEvidence(), "reports", evidence.listReports());
    }

    // ---------- views ----------

    private Map<String, Object> runSummary(String uid) {
        Run run = store.findRunByUid(uid).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runUid", run.runUid());
        out.put("familyId", run.familyId());
        out.put("fingerprint", run.fingerprint());
        out.put("status", run.status());
        out.put("armedFault", run.armedFault());
        out.put("decision", run.decision());
        out.put("terminalEvent", run.terminalEvent());
        out.put("error", run.error());
        out.put("createdAt", run.createdAt().toInstant().toString());
        return out;
    }

    private Map<String, Object> runView(String uid) {
        Run run = store.findRunByUid(uid).orElseThrow(() -> new NotFoundException("运行不存在"));
        Map<String, Object> out = new LinkedHashMap<>(runSummary(uid));
        var revision = store.findRevision(run.fingerprint());
        JsonNode specTree = revision.map(r -> {
            try {
                return mapper.readTree(r.specJson());
            } catch (Exception e) {
                return null;
            }
        }).orElse(null);
        out.put("spec", specTree);
        out.put("steps", store.listSteps(run.id()).stream().map(s -> {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("stepId", s.stepId());
            sm.put("type", s.stepType());
            sm.put("seqNo", s.seqNo());
            sm.put("status", s.status());
            sm.put("cursor", s.cursorValue());
            sm.put("batches", store.listBatches(uid, s.stepId()));
            return sm;
        }).toList());
        out.put("journal", store.listJournal(uid));
        out.put("readChecks", store.listReadChecks(uid));
        out.put("invariants", store.listInvResults(uid));
        out.put("windowWrites", store.listWindowWrites(uid,
                store.listSteps(run.id()).stream()
                        .filter(s -> States.TYPE_DUAL_WRITE.equals(s.stepType()))
                        .findFirst().map(s -> s.stepId()).orElse("")));
        return out;
    }

    @GetMapping("/runs/{uid}")
    public Map<String, Object> run(@PathVariable String uid) {
        return runView(uid);
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
