package local.migrationlab.web;

import local.migrationlab.domain.DefaultDefinitionFactory;
import local.migrationlab.domain.DefinitionStore;
import local.migrationlab.engine.MigrationEngine;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StartupRunner {
    private final DefinitionStore definitions;
    private final DefaultDefinitionFactory factory;
    private final MigrationEngine engine;

    public StartupRunner(DefinitionStore definitions, DefaultDefinitionFactory factory, MigrationEngine engine) {
        this.definitions = definitions;
        this.factory = factory;
        this.engine = engine;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        if (definitions.versions("customer-email-normalization").isEmpty()) {
            definitions.saveInitial(factory.create());
        }
        engine.recoverAtStartup();
    }
}
