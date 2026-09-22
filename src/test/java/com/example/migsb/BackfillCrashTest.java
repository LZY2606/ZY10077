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

class BackfillCrashTest {

    @Test
    void crashMidBatch_resumesFromStableCursorWithoutLossOrDuplication() throws Exception {
        var dir = TestHarness.freshDir("backfill");
        var ctx = TestHarness.start(dir);
        String runUid;
        try {
            var store = ctx.getBean(ControlStore.class);
            var engine = ctx.getBean(MigrationEngine.class);
            ctx.getBean(SampleSeeder.class).seed();
            String fp = store.listFamilies().get(0).headFingerprint();
            var run = engine.createRun(fp, "backfill_orders#mid_batch");
            runUid = run.runUid();
            // 推进：DDL 完成后在回填的 MID_BATCH 故障点退出
            assertThrows(SimulatedCrashException.class, () -> engine.advance(runUid));
        } finally {
            TestHarness.stop(ctx);
        }

        var ctx2 = TestHarness.start(dir);
        try {
            var store = ctx2.getBean(ControlStore.class);
            var scanner = ctx2.getBean(RecoveryScanner.class);
            var report = scanner.scanAll(true);
            var verdict = report.verdicts().get(0);
            assertEquals(RecoveryVerdict.RESUMABLE, verdict.status());
            assertNotNull(verdict.reason());
            assertTrue(verdict.reason().contains("WAL 游标"));

            var engine = ctx2.getBean(MigrationEngine.class);
            engine.advance(runUid);
            // 回填完成后停在双写窗口
            var run = store.findRunByUid(runUid).orElseThrow();
            assertEquals(States.RUN_RUNNING, run.status());
            engine.windowWrite(runUid, "dual_write_window", "BOTH",
                    java.util.Map.of("id", 7001L, "customer_id", 109L, "amount", "1.00", "status", "PAID"));
            // 窗口期间仅落到旧表的新行：关窗对账必须补齐，不能丢失或重复
            engine.windowWrite(runUid, "dual_write_window", "OLD",
                    java.util.Map.of("id", 7002L, "customer_id", 110L, "amount", "55.00", "status", "PAID"));
            engine.closeWindow(runUid, "dual_write_window");
            engine.advance(runUid);

            var finished = store.findRunByUid(runUid).orElseThrow();
            assertEquals(States.RUN_COMMITTED, finished.status());
            long totalRows = store.listBatches(runUid, "backfill_orders").stream()
                    .mapToLong(b -> b.rowCount()).sum();
            assertTrue(totalRows >= 5, "全部原始行必须被计入，实际批次行=" + totalRows);
            assertTrue(store.listInvResults(runUid).stream().allMatch(r -> r.passed()));
        } finally {
            TestHarness.stop(ctx2);
        }
    }
}
