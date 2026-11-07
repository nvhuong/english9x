package com.english9x.workflow.api;

import java.util.List;

/** Several versions submitted together (an Excel import, a course and its lessons). Decided as a unit: all or none. */
public record BundleRef(List<VersionRef> members) {
    public BundleRef {
        members = members == null ? List.of() : List.copyOf(members);
        if (members.isEmpty()) {
            throw new IllegalArgumentException("a bundle needs at least one version");
        }
    }
}
