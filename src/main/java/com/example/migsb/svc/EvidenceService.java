package com.example.migsb.svc;

import com.example.migsb.domain.Fp;
import com.example.migsb.domain.MigrationSpec;
import com.example.migsb.engine.Events;
import com.example.migsb.engine.RecoveryVerdict;
import com.example.migsb.store.ControlStore;
import com.example.migsb.store.Rows.EvidenceReceipt;
import com.example.migsb.store.Rows.Run;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EvidenceService {

    public static final String RULE_VERSION = "migsb-evidence-v1";

    private final ControlStore store;
    private final ObjectMapper mapper;
    private final DefinitionService definitions;
    private final String ruleVersion;

    public EvidenceService(ControlStore store, ObjectMapper mapper, DefinitionService definitions,
                           @Value("${migsb.rule-version:rules-v1}") String ruleVersion) {
        this.store = store;
        this.mapper = mapper;
        this.definitions = definitions;
        this.ruleVersion = ruleVersion;
    }

    public String exportBundle(String fingerprint) {
        var revision = store.findRevision(fingerprint)
                .orElseThrow(() -> new IllegalArgumentException("定义指纹不存在: " + fingerprint));
        ObjectNode bundle = mapper.createObjectNode();
        bundle.put("bundleFormat", RULE_VERSION);
        bundle.put("ruleVersion", ruleVersion);
        bundle.put("exportedAt", Instant.now().toString());
        bundle.set("definition", mapper.createObjectNode()
                .put("familyId", revision.familyId())
                .put("fingerprint", revision.fingerprint())
                .put("parentFingerprint", revision.parentFingerprint() == null ? "" : revision.parentFingerprint())
                .set("specJson", parseTree(revision.specJson())));
        ArrayNode runs = bundle.putArray("runs");
        for (Run run : store.listRunsByFingerprint(fingerprint)) {
            ObjectNode rn = runs.addObject();
            rn.put("runUid", run.runUid());
            rn.put("status", run.status());
            rn.put("armedFault", run.armedFault() == null ? "" : run.armedFault());
            rn.put("terminalEvent", run.terminalEvent() == null ? "" : run.terminalEvent());
            rn.put("decision", run.decision() == null ? "" : run.decision());
            rn.put("error", run.error() == null ? "" : run.error());
            rn.put("createdAt", run.createdAt().toInstant().toString());
            long runId = run.id();
            ArrayNode steps = rn.putArray("steps");
            for (var step : store.listSteps(runId)) {
                ObjectNode sn = steps.addObject();
                sn.put("stepId", step.stepId());
                sn.put("type", step.stepType());
                sn.put("seqNo", step.seqNo());
                sn.put("status", step.status());
                sn.put("cursor", step.cursorValue() == null ? "" : step.cursorValue());
                ArrayNode batches = sn.putArray("batches");
                for (var b : store.listBatches(run.runUid(), step.stepId())) {
                    batches.addObject()
                            .put("batchNo", b.batchNo())
                            .put("pkFrom", b.pkFrom() == null ? "" : b.pkFrom())
                            .put("pkTo", b.pkTo() == null ? "" : b.pkTo())
                            .put("rows", b.rowCount())
                            .put("checksum", b.checksum() == null ? "" : b.checksum());
                }
            }
            ArrayNode journal = rn.putArray("journal");
            for (var j : store.listJournal(run.runUid())) {
                ObjectNode jn = journal.addObject();
                jn.put("event", j.event());
                jn.put("stepId", j.stepId() == null ? "" : j.stepId());
                jn.put("payload", j.payload() == null ? "" : j.payload());
            }
            ArrayNode readChecks = rn.putArray("readChecks");
            for (var rc : store.listReadChecks(run.runUid())) {
                readChecks.addObject().put("label", rc.label()).put("matched", rc.matched());
            }
            ArrayNode invs = rn.putArray("invariants");
            for (var inv : store.listInvResults(run.runUid())) {
                invs.addObject().put("id", inv.invariantId()).put("passed", inv.passed()).put("detail", inv.detail());
            }
        }
        ArrayNode declarations = bundle.putArray("declaredFaultExpectations");
        MigrationSpec spec = definitions.parse(revision.specJson());
        for (var step : spec.steps()) {
            for (var f : step.faults()) {
                declarations.addObject()
                        .put("faultKey", step.id() + "#" + f.id())
                        .put("phase", f.phase())
                        .put("expectVerdict", f.expectVerdict());
            }
        }
        byte[] body = canonical(bundle);
        bundle.put("bundleFingerprint", Fp.sha256Hex(body));
        return bundle.toString();
    }

    private JsonNode parseTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] canonical(JsonNode node) {
        try {
            return mapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 接收原始证据：只追加、不改写；随后按规则版本重放核验并生成派生报告。
     */
    public Map<String, Object> importBundle(String rawJson) {
        JsonNode bundle;
        try {
            bundle = mapper.readTree(rawJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("证据包不是合法 JSON: " + e.getMessage());
        }
        JsonNode defNode = bundle.path("definition");
        String specJson = defNode.path("specJson").toString();
        String claimedFingerprint = defNode.path("fingerprint").asText("");
        String actualFingerprint = definitions.fingerprintOf(specJson);
        List<String> problems = new ArrayList<>();
        List<Map<String, Object>> faultResults = new ArrayList<>();
        if (!claimedFingerprint.equals(actualFingerprint)) {
            problems.add("定义指纹与内容不符: 声称=" + claimedFingerprint + " 实算=" + actualFingerprint);
        }
        String declaredFormat = bundle.path("bundleFormat").asText("");
        if (!RULE_VERSION.equals(declaredFormat)) {
            problems.add("不支持的证据规则版本: " + declaredFormat);
        }
        String claimedBundleFp = bundle.path("bundleFingerprint").asText("");
        ObjectNode forHash = bundle.deepCopy();
        forHash.remove("bundleFingerprint");
        String recomputedFp = Fp.sha256Hex(canonical(forHash));
        if (!claimedBundleFp.equals(recomputedFp)) {
            problems.add("证据包指纹不匹配: 声称=" + claimedBundleFp + " 重算=" + recomputedFp);
        }

        MigrationSpec spec = definitions.parse(specJson);
        verifyRuns(spec, bundle, problems, faultResults);

        String sourceFingerprint = recomputedFp;
        boolean passed = problems.isEmpty();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ruleVersion", RULE_VERSION);
        report.put("sourceFingerprint", sourceFingerprint);
        report.put("definitionFingerprint", actualFingerprint);
        report.put("passed", passed);
        report.put("problems", problems);
        report.put("faultResults", faultResults);
        report.put("verifiedAt", Instant.now().toString());
        String reportJson;
        try {
            reportJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        String prevHash = store.listEvidence().stream()
                .reduce((a, b) -> b).map(EvidenceReceipt::entryHash).orElse(null);
        String rawHash = Fp.sha256Hex(rawJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String entryHash = Fp.sha256Hex(((prevHash == null ? "GENESIS" : prevHash) + "\n" + rawHash).getBytes());

        Map<String, Object> response = new LinkedHashMap<>(report);
        var existing = store.findEvidence(sourceFingerprint);
        if (existing.isPresent()) {
            response.put("duplicate", true);
            response.put("receipt", receiptView(existing.get()));
            store.insertReport(sourceFingerprint, RULE_VERSION, passed, reportJson);
            response.put("reportStored", true);
            return response;
        }
        store.insertEvidence(sourceFingerprint, RULE_VERSION, rawJson, entryHash, prevHash);
        store.insertReport(sourceFingerprint, RULE_VERSION, passed, reportJson);
        response.put("duplicate", false);
        response.put("entryHash", entryHash);
        response.put("prevHash", prevHash);
        response.put("receipt", Map.of("fingerprint", sourceFingerprint, "entryHash", entryHash,
                "prevHash", prevHash == null ? "" : prevHash));
        response.put("reportStored", true);
        return response;
    }

    private Map<String, Object> receiptView(EvidenceReceipt r) {
        return Map.of("fingerprint", r.fingerprint(), "entryHash", r.entryHash(),
                "prevHash", r.prevHash() == null ? "" : r.prevHash(),
                "receivedAt", r.receivedAt().toInstant().toString());
    }

    private void verifyRuns(MigrationSpec spec, JsonNode bundle,
                            List<String> problems, List<Map<String, Object>> faultResults) {
        JsonNode runs = bundle.path("runs");
        Map<String, JsonNode> runByFault = new LinkedHashMap<>();
        for (JsonNode run : runs) {
            String armed = run.path("armedFault").asText("");
            if (!armed.isEmpty()) {
                runByFault.put(armed, run);
            }
        }
        for (JsonNode decl : bundle.path("declaredFaultExpectations")) {
            String key = decl.path("faultKey").asText();
            String expect = decl.path("expectVerdict").asText();
            JsonNode run = runByFault.get(key);
            Map<String, Object> fr = new LinkedHashMap<>();
            fr.put("faultKey", key);
            fr.put("expectVerdict", expect);
            if (run == null) {
                fr.put("observed", "MISSING_RUN");
                fr.put("passed", false);
                problems.add("故障点 " + key + " 没有对应的演练运行");
                faultResults.add(fr);
                continue;
            }
            String observed = observeVerdict(expect, run);
            fr.put("observed", observed);
            boolean ok = observed.equals(expect);
            fr.put("passed", ok);
            if (!ok) {
                problems.add("故障点 " + key + " 预期 " + expect + " 实际观察到 " + observed);
            }
            verifyBatchContinuity(run, problems, key);
            faultResults.add(fr);
        }
        for (JsonNode run : runs) {
            JsonNode journal = run.path("journal");
            if (!hasEvent(journal, Events.RUN_STARTED)) {
                problems.add("run " + run.path("runUid").asText() + " 缺少 RUN_STARTED");
            }
            boolean terminal = hasEvent(journal, Events.RUN_COMPLETED)
                    || hasEvent(journal, Events.RUN_ROLLED_BACK);
            String status = run.path("status").asText("");
            if ("COMMITTED".equals(status) && !hasEvent(journal, Events.RUN_COMPLETED)) {
                problems.add("run " + run.path("runUid").asText() + " 状态 COMMITTED 但缺少 RUN_COMPLETED 终局日志");
            }
            for (JsonNode rc : run.path("readChecks")) {
                if (!rc.path("matched").asBoolean()) {
                    problems.add("run " + run.path("runUid").asText()
                            + " 读路径不一致: " + rc.path("label").asText());
                }
            }
            for (JsonNode inv : run.path("invariants")) {
                if (!inv.path("passed").asBoolean()) {
                    problems.add("run " + run.path("runUid").asText()
                            + " 不变量失败: " + inv.path("id").asText());
                }
            }
            if (terminal && "COMMITTED".equals(status)
                    && !hasEvent(journal, Events.RUN_COMPLETED)) {
                problems.add("run " + run.path("runUid").asText() + " 终局日志与状态矛盾");
            }
        }
    }

    private String observeVerdict(String expect, JsonNode run) {
        JsonNode journal = run.path("journal");
        JsonNode verdictEvents = events(journal, Events.RECOVERY_VERDICT);
        if (verdictEvents.size() > 0) {
            JsonNode last = verdictEvents.get(verdictEvents.size() - 1);
            JsonNode payloadNode = last.path("payload");
            if (payloadNode.isTextual()) {
                try {
                    payloadNode = mapper.readTree(payloadNode.asText());
                } catch (Exception ignored) {
                }
            }
            return payloadNode.path("status").asText("UNKNOWN");
        }
        if (hasEvent(journal, Events.RUN_COMPLETED)) {
            return RecoveryVerdict.FINALIZED;
        }
        if (RecoveryVerdict.UNCERTAIN.equals(expect)) {
            return "HALTED_WITHOUT_VERDICT";
        }
        return "NO_RECOVERY_RECORD";
    }

    private boolean hasEvent(JsonNode journal, String event) {
        return events(journal, event).size() > 0;
    }

    private ArrayNode events(JsonNode journal, String event) {
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode j : journal) {
            if (event.equals(j.path("event").asText())) {
                out.add(j);
            }
        }
        return out;
    }

    private void verifyBatchContinuity(JsonNode run, List<String> problems, String faultKey) {
        for (JsonNode step : run.path("steps")) {
            JsonNode batches = step.path("batches");
            long expected = 0;
            String lastTo = "";
            for (JsonNode b : batches) {
                if (b.path("batchNo").asLong() != expected) {
                    problems.add("故障点 " + faultKey + " 步骤 " + step.path("stepId").asText()
                            + " 批次序号不连续，期望 " + expected);
                }
                String from = b.path("pkFrom").asText("");
                if (expected > 0 && !from.isEmpty() && !lastTo.isEmpty()
                        && comparePk(from) <= comparePk(lastTo)) {
                    problems.add("故障点 " + faultKey + " 存在游标倒退或重叠: " + lastTo + " -> " + from);
                }
                lastTo = b.path("pkTo").asText("");
                expected++;
            }
        }
    }

    private long comparePk(String v) {
        try {
            return Long.parseLong(v);
        } catch (Exception e) {
            return -1;
        }
    }

    public List<Map<String, Object>> listEvidence() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : store.listEvidence()) {
            out.add(receiptView(r));
        }
        return out;
    }

    public List<Map<String, Object>> listReports() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : store.listReports()) {
            out.add(Map.of("sourceFingerprint", r.sourceFingerprint(), "ruleVersion", r.ruleVersion(),
                    "passed", r.passed(), "createdAt", r.createdAt().toInstant().toString(),
                    "reportJson", parseReport(r.reportJson())));
        }
        return out;
    }

    private Object parseReport(String json) {
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
