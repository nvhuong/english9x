package com.english9x.workflow.api;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 409 {@code urn:e9x:problem:unpublished-dependencies}: a version cannot be published while the versions it depends on are
 * not PUBLISHED. {@link #missing()} lists them (for a bundle: those of every member); the problem body carries them as
 * {@code dependencies: [{type, versionId}]}. A dependency that does not exist or that the caller cannot read is listed too.
 */
public class UnpublishedDependencyException extends ErrorResponseException {
    private static final long serialVersionUID = 1L;

    private final transient List<VersionRef> missing;

    public UnpublishedDependencyException(List<VersionRef> missing) {
        super(HttpStatus.CONFLICT, problem(missing), null);
        this.missing = List.copyOf(missing);
    }

    private static ProblemDetail problem(List<VersionRef> missing) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Every version this one depends on must be published first.");
        p.setType(URI.create("urn:e9x:problem:unpublished-dependencies"));
        p.setTitle("Unpublished dependencies");
        p.setProperty("dependencies", missing.stream()
                .map(r -> Map.of("type", r.type(), "versionId", r.versionId().toString())).toList());
        return p;
    }

    public List<VersionRef> missing() {
        return missing;
    }
}
