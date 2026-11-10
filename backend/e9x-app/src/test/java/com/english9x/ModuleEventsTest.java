package com.english9x;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/** The modules that publish domain events expose their {@code api.events} package as the named interface "events". */
class ModuleEventsTest {

    private static final Map<String, String> PUBLISHER = Map.ofEntries(
            Map.entry("AttemptSubmitted", "exam"),
            Map.entry("AttemptScored", "grading"),
            Map.entry("ContentPublished", "workflow"),
            Map.entry("ReviewRequested", "workflow"),
            Map.entry("ReviewChangesRequested", "workflow"),
            Map.entry("ReviewApproved", "workflow"),
            Map.entry("OrderPlaced", "billing"),
            Map.entry("OrderConfirmed", "billing"),
            Map.entry("OrderRejected", "billing"),
            Map.entry("SeatAssigned", "billing"),
            Map.entry("SeatRevoked", "billing"),
            Map.entry("UserRegistered", "identity"));

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
