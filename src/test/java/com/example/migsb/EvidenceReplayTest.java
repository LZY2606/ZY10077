package com.example.migsb;

import com.example.migsb.domain.States;
import com.example.migsb.engine.MigrationEngine;
import com.example.migsb.engine.SimulatedCrashException;
import com.example.migsb.svc.EvidenceService;
import com.example.migsb.svc.SampleSeeder;
import com.example.migsb.store.ControlStore;
import com.example.migsb.store.Rows.Run;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceReplayTest {

    private static final String[] FAULTS = {
            "create_orders_v2#after_apply",
            "backfill_orders#mid_batch",
            "backfill_orders#after_batches",
            "dual_write_window#after_open",
            "dual_write_window#after_reconcile",
            "notify_downstream#after_apply",
            "__finalize__"
    };

    @Test
    void exportedEvidenceReplaysEveryDeclaredFaultPoint() throws Exception {
        var dir = TestHarness.freshDir("evidence-replay");
        List<String> uids = new java.util.ArrayList<>();

        var ctx1 = TestHarness.start(dir);
        String fp;
        try {
            var store = ctx1.getBean(ControlStore.class);
            var engine = ctx1.getBean(MigrationEngine.class);
            ctx1.getBean(SampleSeeder.class).seed();
            fp = store.listFamilies().get(0).headFingerprint();
            for (String fault : FAULTS) {
                Run run = engine.createRun(fp, fault);
                uids.add(run.runUid());
                driveToCrash(engine, run.runUid());
            }
        } finally {
            TestHarness.stop(ctx1);
        }

        var ctx2 = TestHarness.start(dir);
        try {
            var store = ctx2.getBean(ControlStore.class);
            var engine = ctx2.getBean(MigrationEngine.class);
            // 重启后 StartupRecovery 已为每个崩溃运行写入 RECOVERY_VERDICT
            for (String uid : uids) {
                Run run = store.findRunByUid(uid).orElseThrow();
                if (States.RUN_HALTED.equals(run.status())) {
                    engine.decide(uid, States.DECISION_COMMIT, "证据重放：已人工核实副作用生效");
                } else {
                    resume(engine, uid);
                }
                assertEquals(States.RUN_COMMITTED, store.findRunByUid(uid).orElseThrow().status(),
                        "故障点运行 " + uid + " 应能恢复到完整提交");
            }

            var evidence = ctx2.getBean(EvidenceService.class);
            String bundle = evidence.exportBundle(fp);
            var report = evidence.importBundle(bundle);
            assertTrue((Boolean) report.get("passed"),
                    () -> "证据重放应全部通过: " + report.get("problems"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> faultResults =
                    (List<Map<String, Object>>) report.get("faultResults");
            assertEquals(6, faultResults.size());
            assertTrue(faultResults.stream().allMatch(f -> Boolean.TRUE.equals(f.get("passed"))),
                    "每个声明故障点的预期判定都必须被证明");

            String tampered = bundle.replaceFirst("PAID", "XXXX");
            var tamperedReport = evidence.importBundle(tampered);
            assertFalse((Boolean) tamperedReport.get("passed"), "篡改证据必须被包指纹发现");
        } finally {
            TestHarness.stop(ctx2);
        }
    }

    private void driveToCrash(MigrationEngine engine, String uid) {
        for (int i = 0; i < 10; i++) {
            try {
                var res = engine.advance(uid);
                if ("WINDOW_CLOSE".equals(res.get("waitingFor"))) {
                    engine.windowWrite(uid, "dual_write_window", "BOTH",
                            Map.of("id", 99001L + i, "customer_id", 200, "amount", "1.00", "status", "PAID"));
                    engine.windowWrite(uid, "dual_write_window", "OLD",
                            Map.of("id", 97001L + i, "customer_id", 202, "amount", "4.00", "status", "PAID"));
                    engine.closeWindow(uid, "dual_write_window");
                    continue;
                }
                return;
            } catch (SimulatedCrashException expected) {
                return;
            }
        }
        throw new AssertionError("运行未在预期步数内到达故障点");
    }

    private void resume(MigrationEngine engine, String uid) {
        for (int i = 0; i < 10; i++) {
            var res = engine.advance(uid);
            if ("WINDOW_CLOSE".equals(res.get("waitingFor"))) {
                engine.windowWrite(uid, "dual_write_window", "BOTH",
                        Map.of("id", 88001L + i, "customer_id", 201, "amount", "2.00", "status", "PAID"));
                engine.windowWrite(uid, "dual_write_window", "OLD",
                        Map.of("id", 87001L + i, "customer_id", 203, "amount", "5.00", "status", "PAID"));
                engine.closeWindow(uid, "dual_write_window");
                continue;
            }
            return;
        }
        throw new AssertionError("恢复未能完成");
    }
}
