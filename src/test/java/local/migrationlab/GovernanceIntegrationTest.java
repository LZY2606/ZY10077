package local.migrationlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import local.migrationlab.domain.DefinitionStore;
import local.migrationlab.domain.EvidenceService;
import local.migrationlab.domain.RehearsalService;
import local.migrationlab.engine.MigrationEngine;
import local.migrationlab.support.ApiException;
import local.migrationlab.support.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "migration-lab.database-url=jdbc:h2:mem:governance-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE"
})
class GovernanceIntegrationTest {
    @Autowired DefinitionStore definitions;
    @Autowired MigrationEngine engine;
    @Autowired EvidenceService evidence;
    @Autowired RehearsalService rehearsal;

    @Test
    void staleBranchKeepsOldFingerprintBoundRunsAndReportsConflict() {
        Map<String, Object> first = definitions.latest("customer-email-normalization");
        String original = (String) first.get("fingerprint");
        String modified = Json.write(first.get("content")).replace("客户邮箱规范化结构迁移", "客户邮箱规范化结构迁移-分支A");
        Map<String, Object> branched = definitions.saveBranch(
                "customer-email-normalization", original, modified);

        String staleTitle = Json.write(first.get("content")).replace("客户邮箱规范化结构迁移", "客户邮箱规范化结构迁移-分支B");
        assertThatThrownBy(() -> definitions.saveBranch(
                "customer-email-normalization", original, staleTitle))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("newer branch");
        Map<String, Object> run = engine.createRun(original, "old fingerprint", "simulate");
        Map<String, Object> exported = engine.detail((String) run.get("id"));

        assertThat(run.get("fingerprint")).isEqualTo(original);
        assertThat((Integer) branched.get("version")).isGreaterThan((Integer) first.get("version"));
        assertThat(exported.get("fingerprint")).isEqualTo(original);
    }

    @Test
    void importedEvidenceIsStoredRawWithRuleVersionAndProvenance() {
        String fingerprint = (String) definitions.latest("customer-email-normalization").get("fingerprint");
        Map<String, Object> run = engine.createRun(fingerprint, "evidence", "simulate");
        engine.start((String) run.get("id"), null, "simulate");
        Map<String, Object> exported = evidence.exportRun((String) run.get("id"));

        Map<String, Object> imported = evidence.importBundle(Json.write(exported));

        assertThat(imported.get("analysisStatus")).isEqualTo("PROVEN");
        assertThat(imported.get("ruleVersion")).isEqualTo("migration-lab.rules.v1");
        assertThat(imported.get("sourceFingerprint")).isEqualTo(fingerprint);
        assertThat((String) imported.get("receivedSha256")).isNotBlank();
        assertThat(Json.write(imported.get("raw"))).contains("RUN_COMPLETED");
    }

    @Test
    void rehearsalProvesEveryDeclaredFaultPointForResumeAndRollback() {
        String fingerprint = (String) definitions.latest("customer-email-normalization").get("fingerprint");

        Map<String, Object> suite = rehearsal.runSuite(fingerprint);

        assertThat(suite.get("status")).isEqualTo("PASS");
        assertThat(suite.get("results")).asList().hasSize(26);
        for (Object item : (Iterable<?>) suite.get("results")) {
            Map<?, ?> result = (Map<?, ?>) item;
            assertThat(result.get("passed")).isEqualTo(true);
            assertThat(result.get("stateAfterDecision"))
                    .isEqualTo("resume".equals(result.get("decision")) ? "COMPLETED" : "ROLLED_BACK");
        }
    }
}
