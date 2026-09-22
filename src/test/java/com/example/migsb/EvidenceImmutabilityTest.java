package com.example.migsb;

import com.example.migsb.svc.EvidenceService;
import com.example.migsb.svc.SampleSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceImmutabilityTest {

    @Test
    void rawEvidenceIsAppendOnlyAndReimportIsIdempotent() throws Exception {
        var dir = TestHarness.freshDir("evidence-imm");
        var ctx = TestHarness.start(dir);
        try {
            ctx.getBean(SampleSeeder.class).seed();
            var evidence = ctx.getBean(EvidenceService.class);
            var store = ctx.getBean(com.example.migsb.store.ControlStore.class);
            String fp = store.listFamilies().get(0).headFingerprint();
            String bundle = evidence.exportBundle(fp);
            var first = evidence.importBundle(bundle);
            assertFalse((Boolean) first.get("duplicate"));
            var second = evidence.importBundle(bundle);
            assertTrue((Boolean) second.get("duplicate"));
            assertEquals(1, store.listEvidence().size(), "同一证据重复接收不得新增原始记录");

            var jdbc = ctx.getBean(JdbcTemplate.class);
            assertThrows(Exception.class, () ->
                    jdbc.update("UPDATE evidence_receipt SET raw_json='x' WHERE fingerprint=?",
                            first.get("sourceFingerprint")));
            assertThrows(Exception.class, () ->
                    jdbc.update("DELETE FROM evidence_receipt WHERE fingerprint=?",
                            first.get("sourceFingerprint")));
        } finally {
            TestHarness.stop(ctx);
        }
    }
}
