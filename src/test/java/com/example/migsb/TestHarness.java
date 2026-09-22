package com.example.migsb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public final class TestHarness {

    private TestHarness() {}

    public static Path freshDir(String name) throws IOException {
        Path dir = Path.of("target", "test-data", name + "-" + System.nanoTime());
        if (Files.exists(dir)) {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        Files.createDirectories(dir);
        return dir;
    }

    public static ConfigurableApplicationContext start(Path dbDir) {
        return new SpringApplicationBuilder(MigsbApplication.class)
                .profiles("crashtest")
                .web(WebApplicationType.NONE)
                .properties(java.util.Map.of(
                        "spring.datasource.url", "jdbc:h2:file:" + dbDir.resolve("migsb").toAbsolutePath()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE;AUTO_RECONNECT=TRUE",
                        "spring.sql.init.mode", "always",
                        "server.port", "0"
                ))
                .run();
    }

    public static void stop(ConfigurableApplicationContext ctx) {
        if (ctx != null) {
            ctx.close();
        }
    }
}
