package com.english9x.workflow.api;

import java.util.UUID;

/** One content version: the content type (the name of a {@link ReviewableType}) and the id of the version row. */
public record VersionRef(String type, UUID versionId) {
    public VersionRef {
        if (type == null || type.isBlank() || versionId == null) {
            throw new IllegalArgumentException("a version reference needs a type and an id");
        }
    }
}
