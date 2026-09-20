package local.migrationlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import local.migrationlab.support.Json;
import org.junit.jupiter.api.Test;

class ProcessRestartIntegrationTest {
    @Test
    void realHaltRestartClassifiesCommittedBatchAndCanComplete() throws Exception {
        Path directory = Files.createTempDirectory("migration-lab-halt");
        Path database = directory.resolve("halt-lab");
        String dbUrl = "jdbc:h2:file:" + database.toAbsolutePath() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        int port = 5317;

        Process first = startProcess(dbUrl, port);
        waitForHealth(port);
        String fingerprint = get("/api/definitions/customer-email-normalization").get("fingerprint").toString();
        Map<String, Object> created = post("/api/runs", Map.of("fingerprint", fingerprint, "exitMode", "halt"));
        String runId = created.get("id").toString();
        try {
            post("/api/runs/" + runId + "/start", Map.of(
                    "faultPoint", "backfill_customers.batch_after", "exitMode", "halt"));
        } catch (java.io.IOException expectedOnHalt) {
            // Runtime.halt closes the process before an HTTP response is returned.
        }
        assertThat(first.waitFor()).isEqualTo(86);

        Process second = startProcess(dbUrl, port);
        waitForHealth(port);
        Map<String, Object> recovered = get("/api/runs/" + runId);
        assertThat(recovered.get("status")).isEqualTo("READY");
        assertThat(recovered.get("currentStep")).isEqualTo("backfill_customers");
        assertThat(((Number) recovered.get("cursorId")).longValue()).isEqualTo(25L);

        Map<String, Object> completed = post("/api/runs/" + runId + "/resume", Map.of());
        assertThat(completed.get("status")).isEqualTo("COMPLETED");
        second.destroyForcibly();
        second.waitFor();
    }

    private Process startProcess(String dbUrl, int port) throws Exception {
        String classPath = System.getProperty("java.class.path");
        ProcessBuilder builder = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "-cp", classPath,
                "local.migrationlab.MigrationLabApplication",
                "--server.port=" + port,
                "--migration-lab.database-url=" + dbUrl
        );
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    private void waitForHealth(int port) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health")).build();
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(Duration.ofMillis(200).toMillis());
        }
        throw new AssertionError("Application did not become healthy");
    }

    private Map<String, Object> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:5317" + path)).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return Json.mapper().readValue(response.body(), Map.class);
    }

    private Map<String, Object> post(String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:5317" + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : Json.write(body)))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return Json.mapper().readValue(response.body(), Map.class);
    }
}
