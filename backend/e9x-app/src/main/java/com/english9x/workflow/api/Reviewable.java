package com.english9x.workflow.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the workflow needs to know about a content version. Built by the owning content module from its own row, never
 * from request input: {@code authorId} is the creator recorded when the version was made.
 */
public interface Reviewable {

    /** The content type, the same name the owning {@link ReviewableType} answers to. */
    String type();

    UUID versionId();

    /** The organisation that owns the content; empty = English9x content. */
    Optional<UUID> ownerOrgId();

    /** The exam the version belongs to (an exam-scoped REVIEWER can only act on this exam). */
    String examCode();

    /** Who created the version; may never review it. */
    UUID authorId();

    ContentStatus status();

    /** Versions this one needs PUBLISHED before it can be (a test needs its questions, a course its lessons). */
    List<VersionRef> dependencies();
}
