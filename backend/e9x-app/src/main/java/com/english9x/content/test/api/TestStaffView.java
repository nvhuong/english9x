package com.english9x.content.test.api;

import com.english9x.workflow.api.ContentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The structure of a test version for the people who build it: which group versions (by id) are asked in which part. It holds no question content and no
 * solution; the groups are read through {@code QuestionGroupVersions}.
 *
 * @param ownerOrgId empty = English9x
 * @param maxResumes null = the exam's default
 */
public record TestStaffView(UUID versionId, UUID testId, int versionNo, ContentStatus status, Optional<UUID> ownerOrgId, String examCode, String variant,
                            String title, Integer maxResumes, List<Part> parts, UUID createdBy, Instant createdAt, Instant updatedAt) {

    public record Part(int position, String sectionCode, int durationSec, List<UUID> groupVersionIds) {}
}
