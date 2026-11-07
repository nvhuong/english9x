package com.english9x.workflow;

import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.Reviewable;
import com.english9x.workflow.api.ReviewableType;
import com.english9x.workflow.api.VersionRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A test-only content type "item" over the throw-away tables test_items / test_item_deps (created by the test as superuser,
 * with the generic lifecycle trigger attached the way P2 attaches it). Locks exactly as the {@link ReviewableType} contract asks.
 */
class TestItems implements ReviewableType {

    private final JdbcTemplate jdbc;
    /** Called after a dependency was read and locked FOR SHARE (lets a test hold the transaction open at that point). */
    volatile Consumer<UUID> afterDependencyLocked = id -> {};

    TestItems(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return "item";
    }

    @Override
    public Optional<Reviewable> lockForReview(UUID versionId) {
        return jdbc.query("select id, owner_org_id, exam_code, author_id, status from test_items where id = ? for update",
                (rs, n) -> (Reviewable) new Item(rs.getObject("id", UUID.class), Optional.ofNullable(rs.getObject("owner_org_id", UUID.class)),
                        rs.getString("exam_code"), rs.getObject("author_id", UUID.class), ContentStatus.valueOf(rs.getString("status")),
                        jdbc.query("select dep_id from test_item_deps where item_id = ? order by dep_id",
                                (r, i) -> new VersionRef("item", r.getObject("dep_id", UUID.class)), rs.getObject("id", UUID.class))),
                versionId).stream().findFirst();
    }

    @Override
    public Optional<ContentStatus> lockStatus(UUID versionId) {
        Optional<ContentStatus> status = jdbc.query("select status from test_items where id = ? for share",
                (rs, n) -> ContentStatus.valueOf(rs.getString(1)), versionId).stream().findFirst();
        afterDependencyLocked.accept(versionId);
        return status;
    }

    @Override
    public Optional<VersionStatus> readStatus(UUID versionId) {
        return jdbc.query("select status, owner_org_id from test_items where id = ?",
                (rs, n) -> new VersionStatus(ContentStatus.valueOf(rs.getString(1)), Optional.ofNullable(rs.getObject(2, UUID.class))),
                versionId).stream().findFirst();
    }

    @Override
    public void setStatus(UUID versionId, ContentStatus status) {
        jdbc.update("update test_items set status = ? where id = ?", status.name(), versionId);
    }

    record Item(UUID versionId, Optional<UUID> ownerOrgId, String examCode, UUID authorId, ContentStatus status,
                List<VersionRef> dependencies) implements Reviewable {
        @Override
        public String type() {
            return "item";
        }
    }
}
