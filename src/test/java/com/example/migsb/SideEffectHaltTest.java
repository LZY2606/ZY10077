package com.example.migsb;

import com.example.migsb.domain.States;
import com.example.migsb.engine.MigrationEngine;
import com.example.migsb.engine.RecoveryScanner;
import com.example.migsb.engine.RecoveryVerdict;
import com.example.migsb.engine.SimulatedCrashException;
import com.example.migsb.svc.SampleSeeder;
import com.example.migsb.store.ControlStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SideEffectHaltTest {

    @Test
    void unprovableSideEffectHaltsAndAcceptsHumanCommitOrCompensation() throws Exception {
        var dir = TestHarness.freshDir("sideeffect");
        String runUid;
        var ctx = TestHarness.start(dir);
        try {
            var store = ctx.getBean(ControlStore.class);
            var engine = ctx.getBean(MigrationEngine.class);
            ctx.getBean(SampleSeeder.class).seed();
            String fp = store.listFamilies().get(0).headFingerprint();
            var run = engine.createRun(fp, "notify_downstream#after_apply");
            runUid = run.runUid();
            assertThrows(SimulatedCrashException.class, () -> {
                engine.advance(runUid);
                completeWindow(engine, runUid);
                engine.advance(runUid);
            });
        } finally {
            TestHarness.stop(ctx);
        }

        var ctx2 = TestHarness.start(dir);
        try {
            var store = ctx2.getBean(ControlStore.class);
            var scanner = ctx2.getBean(RecoveryScanner.class);
            var report = scanner.scanAll(true);
            assertEquals(RecoveryVerdict.UNCERTAIN, report.verdicts().get(0).status());
            assertEquals(States.RUN_HALTED, store.findRunByUid(runUid).orElseThrow().status());

            var engine = ctx2.getBean(MigrationEngine.class);
            assertThrows(RuntimeException.class, () -> engine.advance(runUid),
                    "HALTED 状态必须拒绝自动重跑");

            // 人工核实副作用生效 -> 提交
            engine.decide(runUid, States.DECISION_COMMIT, "已在外部系统确认通知送达");
            assertEquals(States.RUN_COMMITTED, store.findRunByUid(runUid).orElseThrow().status());
        } finally {
            TestHarness.stop(ctx2);
        }
    }

    private static void completeWindow(MigrationEngine engine, String runUid) {
        engine.windowWrite(runUid, "dual_write_window", "BOTH",
                java.util.Map.of("id", 8001L, "customer_id", 120L, "amount", "2.00", "status", "PAID"));
        engine.windowWrite(runUid, "dual_write_window", "OLD",
                java.util.Map.of("id", 8002L, "customer_id", 121L, "amount", "3.00", "status", "PAID"));
        engine.closeWindow(runUid, "dual_write_window");
    }
}
