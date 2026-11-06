package com.english9x.platform.jobs.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A claimed job as handed to its handler.
 *
 * @param attempt 1 for the first run, 2 for the first retry, and so on (a lease that expired after a crash also counts)
 */
public record Job(UUID id, String type, JsonNode payload, int attempt, Instant createdAt) {

    /** Deliberately leaves the payload out: it may hold user data and records are easy to log by accident. */
    @Override
    public String toString() {
        return "Job[id=" + id + ", type=" + type + ", attempt=" + attempt + "]";
    }
}
