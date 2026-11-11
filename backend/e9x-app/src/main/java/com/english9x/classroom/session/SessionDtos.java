package com.english9x.classroom.session;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Bodies of the schedule endpoints. */
public final class SessionDtos {

    private SessionDtos() {}

    @Schema(name = "SessionMode", enumAsRef = true, description = "OFFLINE (in a room) or ONLINE (a meeting link)")
    public enum Mode { OFFLINE, ONLINE }

    @Schema(name = "CreateSessionRequest", description = "an ONLINE session needs an https meetingUrl (any domain); an OFFLINE one takes a room and no link")
    public record CreateSessionRequest(
            @NotNull Mode mode,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @Schema(nullable = true) @Size(max = 100) String room,
            @Schema(nullable = true) @Size(max = 500) String meetingUrl) {}

    @Schema(name = "SessionView")
    public record SessionView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID classId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Mode mode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant startsAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant endsAt,
            @Schema(nullable = true) String room,
            @Schema(nullable = true) String meetingUrl) {}

    @Schema(name = "ScheduleRole", enumAsRef = true, description = "the person's part in the session: TEACHER of its class or LEARNER in it")
    public enum Role { TEACHER, LEARNER }

    @Schema(name = "ScheduleItem")
    public record ScheduleItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID classId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String className,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Mode mode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant startsAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant endsAt,
            @Schema(nullable = true) String room,
            @Schema(nullable = true) String meetingUrl,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Role role,
            @Schema(nullable = true, description = "my own attendance mark (PRESENT / ABSENT / LATE / EXCUSED), once the teacher recorded it") String attendance) {}
}
