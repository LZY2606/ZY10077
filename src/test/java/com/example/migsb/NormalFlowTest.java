package com.example.migsb;

import com.example.migsb.domain.States;
import com.example.migsb.engine.MigrationEngine;
import com.example.migsb.svc.DefinitionService;
import com.example.migsb.svc.SampleSeeder;
import com.example.migsb.store.ControlStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NormalFlowTest {

    @Test
    void fullMigration_completesWithInvariantsReadPathsAndTerminalEvent() throws Exception {
        var dir = TestHarness.freshDir("normal");
        var ctx = TestHarness.start(dir);
        try {
            var store = ctx.getBean(ControlStore.class);
            var engine = ctx.getBean(MigrationEngine.class);
            ctx.getBean(SampleSeeder.class).seed();
            String fp = store.listFamilies().get(0).headFingerprint();

            var run = engine.createRun(fp, null);
            engine.advance(run.runUid());

            // 进入双写窗口：制造新旧一致与旧表晚到行
            engine.windowWrite(run.runUid(), "dual_write_window", "BOTH",
                    java.util.Map.of("id", 6001L, "customer_id", 105L, "amount", "88.00", "status", "PAID"));
            engine.windowWrite(run.runUid(), "dual_write_window", "OLD",
                    java.util.Map.of("id", 6002L, "customer_id", 106L, "amount", "9.99", "status", "PAID"));
            engine.closeWindow(run.runUid(), "dual_write_window");
            engine.advance(run.runUid());

            var finished = store.findRunByUid(run.runUid()).orElseThrow();
            assertEquals(States.RUN_COMMITTED, finished.status());
            assertEquals("RUN_COMPLETED", finished.terminalEvent());
            assertTrue(store.listInvResults(run.runUid()).stream().allMatch(r -> r.passed()));
            assertTrue(store.listReadChecks(run.runUid()).stream().allMatch(r -> r.matched()));
        } finally {
            TestHarness.stop(ctx);
        }
    }
}
