package com.english9x.workflow.api;

/**
 * Lifecycle of a content version (spec §7.1). The database stores the name in the {@code status} column of every
 * {@code *_versions} table; {@code e9x_guard_version_lifecycle()} enforces the allowed moves.
 */
public enum ContentStatus {
    DRAFT,
    IN_REVIEW,
    CHANGES_REQUESTED,
    PUBLISHED,
    ARCHIVED;

    /** The author may edit the version in these two states; in every other state it is frozen. */
    public boolean editable() {
        return this == DRAFT || this == CHANGES_REQUESTED;
    }
}
