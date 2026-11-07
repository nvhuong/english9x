package com.english9x.workflow.api;

import java.util.List;

/** A version cannot be published while the versions it depends on are not PUBLISHED. {@link #missing()} lists them. */
public class UnpublishedDependencyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient List<VersionRef> missing;

    public UnpublishedDependencyException(List<VersionRef> missing) {
        super("unpublished dependencies: " + missing.size());
        this.missing = List.copyOf(missing);
    }

    public List<VersionRef> missing() {
        return missing;
    }
}
