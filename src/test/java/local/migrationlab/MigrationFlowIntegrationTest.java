package local.migrationlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import local.migrationlab.domain.DefinitionStore;
import local.migrationlab.domain.RunStore;
import local.migrationlab.engine.MigrationEngine;
import local.migrationlab.support.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "migration-lab.database-url=jdbc:h2:mem:flow-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE"
})
class MigrationFlowIntegrationTest {
    @Autowired DefinitionStore definitions;
    @Autowired MigrationEngine engine;
    @Autowired RunStore runs;

    @Test
    void completesStructureDataAndLogProof() {
        Map<String, Object> definition = definitions.latest("customer-email-normalization");
        String fingerprint = (String) definition.get("fingerprint");
        Map<String, Object> run = engine.createRun(fingerprint, "complete", "simulate");

        Map<String, Object> completed = engine.start((String) run.get("id"), null, "simulate");

        assertThat(completed.get("status")).isEqualTo("COMPLETED");
        Map<String, Object> detail = engine.detail((String) run.get("id"));
        assertThat(detail.get("batches")).asList().hasSize(6);
        assertThat(detail.get("verifications")).asList().isNotEmpty();
        assertThat(engine.verifyEventChain((String) run.get("id"))).isTrue();
    }

    @Test
    void recoversAfterBatchCrashWithoutLosingConcurrentInsert() {
        String fingerprint = (String) definitions.latest("customer-email-normalization").get("fingerprint");
        Map<String, Object> run = engine.createRun(fingerprint, "crash", "simulate");
        String runId = (String) run.get("id");
        engine.start(runId, "backfill_customers.batch_after", "simulate");
        assertThat(runs.getRun(runId).get("status")).isEqualTo("RUNNING");
        assertThat(runs.getRun(runId).get("currentStep")).isEqualTo("backfill_customers");
        assertThat(runs.getRun(runId).get("cursorId")).isEqualTo(0L);

        engine.testInsert(runId, "during.recovery@Example.com");
        engine.recoverAtStartup();
        Map<String, Object> recovered = runs.getRun(runId);
        assertThat(recovered.get("status")).isEqualTo("READY");
        assertThat(recovered.get("cursorId")).isEqualTo(25L);
        Map<String, Object> completed = engine.resume(runId);

        assertThat(completed.get("status")).isEqualTo("COMPLETED");
        Map<String, Object> detail = engine.detail(runId);
        assertThat(detail.get("batches")).asList().isNotEmpty();
        assertThat(Json.write(detail)).contains("during.recovery@Example.com");
    }

    @Test
    void rollbackCompensatesEachDeclaredFaultPoint() {
        String fingerprint = (String) definitions.latest("customer-email-normalization").get("fingerprint");
        Map<String, Object> run = engine.createRun(fingerprint, "rollback", "simulate");
        String runId = (String) run.get("id");
        engine.start(runId, "cutover_read_path.after", "simulate");

        Map<String, Object> rolledBack = engine.rollback(runId);

        assertThat(rolledBack.get("status")).isEqualTo("ROLLED_BACK");
        assertThat(runs.events(runId).stream().anyMatch(event -> "ROLLBACK_COMMITTED".equals(event.get("type")))).isTrue();
    }
}
