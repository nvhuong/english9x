package com.english9x;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/** The modules that publish domain events expose their {@code api.events} package as the named interface "events". */
class ModuleEventsTest {

    private static final Map<String, String> PUBLISHER = Map.of(
            "AttemptSubmitted", "exam",
            "AttemptScored", "grading",
            "ContentPublished", "workflow",
            "OrderConfirmed", "billing",
            "OrderRejected", "billing",
            "SeatAssigned", "billing",
            "SeatRevoked", "billing");

    @Test
    void everyEventIsExposedByItsPublisher() {
        ApplicationModules modules = ApplicationModules.of(E9xApplication.class);
        PUBLISHER.forEach((event, name) -> {
            ApplicationModule module = modules.getModuleByName(name)
                    .orElseThrow(() -> new AssertionError("module '" + name + "' (publisher of " + event + ") does not exist"));
            var events = module.getNamedInterfaces().getByName("events")
                    .orElseThrow(() -> new AssertionError(name + " exposes no named interface 'events'"));
            String fqn = "com.english9x." + name + ".api.events." + event;
            assertTrue(events.asJavaClasses().anyMatch(c -> c.getName().equals(fqn)),
                    fqn + " is not part of the module's exposed 'events' interface");
        });
    }
}
