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

class DualWriteCrashTest {

    @Test
    void crashAfterWindowOpen_reopensAndReconcilesOldOnlyWrites() throws Exception {
        var dir = TestHarness.freshDir("dualwrite");
        String runUid;
        var ctx = TestHarness.start(dir);
        try {
            var store = ctx.getBean(ControlStore.class);
            var engine = ctx.getBean(MigrationEngine.class);
            ctx.getBean(SampleSeeder.class).seed();
            String fp = store.listFamilies().get(0).headFingerprint();
            var run = engine.createRun(fp, "dual_write_window#after_open");
            runUid = run.runUid();
            assertThrows(SimulatedCrashException.class, () -> engine.advance(runUid));
        } finally {
            TestHarness.stop(ctx);
        }

        var ctx2 = TestHarness.start(dir);
        try {
            var store = ctx2.getBean(ControlStore.class);
            var scanner = ctx2.getBean(RecoveryScanner.class);
            var report = scanner.scanAll(true);
            assertEquals(RecoveryVerdict.WINDOW_OPEN, report.verdicts().get(0).status());

            var engine = ctx2.getBean(MigrationEngine.class);
            // 重启后仅写旧表的新行
            engine.windowWrite(runUid, "dual_write_window", "OLD",
                    java.util.Map.of("id", 9001L, "customer_id", 130L, "amount", "3.00", "status", "PAID"));
            engine.closeWindow(runUid, "dual_write_window");
            engine.advance(runUid);
            assertEquals(States.RUN_COMMITTED, store.findRunByUid(runUid).orElseThrow().status());
        } finally {
            TestHarness.stop(ctx2);
        }
    }
}
