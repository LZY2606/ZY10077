package com.example.migsb.engine;

import com.example.migsb.domain.MigrationSpec;
import com.example.migsb.store.ControlStore;
import com.example.migsb.store.Rows.Run;
import com.example.migsb.store.Rows.Step;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

abstract class EngineSupport {

    protected final ControlStore store;
    protected final SandboxService sandbox;
    protected final ObjectMapper mapper;
    protected final CrashSimulator crash;

    protected EngineSupport(ControlStore store, SandboxService sandbox,
                            ObjectMapper mapper, CrashSimulator crash) {
        this.store = store;
        this.sandbox = sandbox;
        this.mapper = mapper;
        this.crash = crash;
    }

    protected MigrationSpec specOf(Run run) {
        var revision = store.findRevision(run.fingerprint())
                .orElseThrow(() -> new EngineException("运行绑定的定义指纹已不存在: " + run.fingerprint()));
        try {
            return mapper.readValue(revision.specJson(), MigrationSpec.class);
        } catch (Exception e) {
            throw new EngineException("无法解析定义: " + e.getMessage(), e);
        }
    }

    protected List<Step> steps(Run run) {
        return store.listSteps(run.id());
    }

    protected void journal(Run run, String stepId, String event, Map<String, Object> payload) {
        try {
            store.appendJournal(run.runUid(), stepId, event,
                    payload == null ? null : mapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new EngineException("日志写入失败: " + e.getMessage(), e);
        }
    }

    protected Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    protected void faultIfArmed(Run run, Step step, String phase) {
        String armed = run.armedFault();
        if (armed == null || step == null) {
            return;
        }
        String key = step.stepId() + "#";
        if (!armed.startsWith(key)) {
            return;
        }
        String faultId = armed.substring(key.length());
        var def = specOf(run).steps().stream()
                .filter(s -> s.id().equals(step.stepId()))
                .findFirst()
                .orElse(null);
        if (def == null) {
            return;
        }
        var match = def.faults().stream()
                .filter(f -> f.id().equals(faultId) && f.phase().equals(phase))
                .findFirst();
        match.ifPresent(f -> consumeAndCrash(run, step, f, phase));
    }

    protected void crashAtFinalize(Run run) {
        boolean consumed = store.listJournal(run.runUid()).stream()
                .anyMatch(j -> "FAULT_CONSUMED".equals(j.event())
                        && j.payload() != null && j.payload().contains("__finalize__"));
        if (consumed) {
            return;
        }
        journal(run, null, "FAULT_CONSUMED", map("faultKey", "__finalize__", "phase", "BEFORE_TERMINAL"));
        crash.trigger(run.runUid(), null, "__finalize__", "BEFORE_TERMINAL");
    }

    private void consumeAndCrash(Run run, Step step, MigrationSpec.FaultDef f, String phase) {
        String key = step.stepId() + "#" + f.id();
        boolean consumed = store.listJournal(run.runUid()).stream()
                .anyMatch(j -> "FAULT_CONSUMED".equals(j.event())
                        && j.payload() != null && j.payload().contains(key));
        if (consumed) {
            return;
        }
        journal(run, step.stepId(), "FAULT_CONSUMED", map("faultKey", key, "phase", phase));
        crash.trigger(run.runUid(), step.stepId(), f.id(), phase);
    }
}
