package com.example.migsb.svc;

import com.example.migsb.store.ControlStore;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class SampleSeeder {

    private static final Logger log = LoggerFactory.getLogger(SampleSeeder.class);

    private final ControlStore store;
    private final DefinitionService definitions;

    public SampleSeeder(ControlStore store, DefinitionService definitions) {
        this.store = store;
        this.definitions = definitions;
    }

    @jakarta.annotation.PostConstruct
    public void seed() {
        if (!store.listFamilies().isEmpty()) {
            return;
        }
        try {
            String json = new String(new ClassPathResource("sample-definition.json")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var result = definitions.submit(json, null, null, false);
            log.info("已播种示例迁移定义 family={} fingerprint={}", result.familyId(), result.fingerprint());
        } catch (Exception e) {
            log.error("示例定义播种失败", e);
        }
    }
}
