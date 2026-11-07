package com.english9x.platform.e2e;

import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.Reviewable;
import com.english9x.workflow.api.ReviewableType;
import com.english9x.workflow.api.VersionRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A stand-in content type "e2e_doc" for the E2E suite, until P2 brings real content (CMS screens, P2.9). It is a real
 * {@link ReviewableType}: the review workflow, Authz, the lifecycle trigger and the audit log all run for it exactly as they will for
 * questions and courses. Its table {@code e2e_docs} is created by the seed (see {@link E2eReviewSeed}), never by a migration, so
 * it exists only on a stack started with the {@code e2e} profile.
 */
@Component
@Profile("e2e")
class E2eDocs implements ReviewableType {

    static final String TYPE = "e2e_doc";

    private final JdbcTemplate jdbc;

    E2eDocs(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Optional<Reviewable> lockForReview(UUID versionId) {
        return jdbc.query("select id, owner_org_id, exam_code, author_id, status from e2e_docs where id = ? for update",
                (rs, n) -> (Reviewable) new Doc(rs.getObject("id", UUID.class), Optional.ofNullable(rs.getObject("owner_org_id", UUID.class)),
                        rs.getString("exam_code"), rs.getObject("author_id", UUID.class), ContentStatus.valueOf(rs.getString("status"))),
                versionId).stream().findFirst();
    }

    @Override
    public Optional<ContentStatus> lockStatus(UUID versionId) {
        return jdbc.query("select status from e2e_docs where id = ? for share", (rs, n) -> ContentStatus.valueOf(rs.getString(1)), versionId).stream().findFirst();
    }

    @Override
    public Optional<VersionStatus> readStatus(UUID versionId) {
        return jdbc.query("select status, owner_org_id from e2e_docs where id = ?",
                (rs, n) -> new VersionStatus(ContentStatus.valueOf(rs.getString(1)), Optional.ofNullable(rs.getObject(2, UUID.class))), versionId)
                .stream().findFirst();
    }

    @Override
    public void setStatus(UUID versionId, ContentStatus status) {
        jdbc.update("update e2e_docs set status = ? where id = ?", status.name(), versionId);
    }

    record Doc(UUID versionId, Optional<UUID> ownerOrgId, String examCode, UUID authorId, ContentStatus status) implements Reviewable {
        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public List<VersionRef> dependencies() {
            return List.of();
        }
    }
}
