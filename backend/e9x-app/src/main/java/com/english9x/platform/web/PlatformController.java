package com.english9x.platform.web;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The one platform endpoint: a liveness probe for clients, and the seed of the generated API client. */
@RestController
class PlatformController {

    @Schema(name = "Pong")
    record Pong(String status) {}

    @GetMapping(value = "/api/platform/ping", produces = "application/json")
    Pong ping() {
        return new Pong("ok");
    }
}
