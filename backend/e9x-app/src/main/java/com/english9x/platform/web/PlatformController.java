package com.english9x.platform.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The one platform endpoint: a liveness probe for clients, and the seed of the generated API client. */
@RestController
class PlatformController {

    @Schema(name = "Pong")
    record Pong(String status) {}

    @Schema(name = "PublicConfig")
    record PublicConfig(long learningHeartbeatIntervalSeconds, long examAutosaveIntervalSeconds) {}

    private final Duration heartbeat;
    private final Duration autosave;

    PlatformController(@Value("${e9x.learning.heartbeat-interval:30s}") Duration heartbeat, @Value("${e9x.exam.autosave-interval:5s}") Duration autosave) {
        this.heartbeat = heartbeat;
        this.autosave = autosave;
    }

    /** The intervals the browser paces itself by (e9x.learning.heartbeat-interval, e9x.exam.autosave-interval); no secrets, no auth. */
    @GetMapping(value = "/api/config/public", produces = "application/json")
    PublicConfig publicConfig() {
        return new PublicConfig(heartbeat.toSeconds(), autosave.toSeconds());
    }

    @GetMapping(value = "/api/platform/ping", produces = "application/json")
    Pong ping() {
        return new Pong("ok");
    }
}
