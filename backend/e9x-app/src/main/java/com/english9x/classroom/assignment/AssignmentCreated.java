package com.english9x.classroom.assignment;

import java.util.List;
import java.util.UUID;

/** An assignment was given to a class. Published after commit; {@code recipients} are the members the service saw, the listener re-checks each one. */
public record AssignmentCreated(UUID assignmentId, List<UUID> recipients) {}
