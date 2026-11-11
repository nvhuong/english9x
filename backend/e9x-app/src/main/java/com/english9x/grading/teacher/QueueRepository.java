package com.english9x.grading.teacher;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The teacher's and the administrator's side of {@code grading_queue_items}, run on the request connection: Row-Level Security decides what each of them sees (a teacher
 * the OPEN items of the side they are a teacher of and the ones they hold, the administrator of a side all of it) and who may change what. The conditions repeated in the
 * statements below are not the protection, only the shape of the answer (a teacher's list is not the administrator's list).
 */
@Repository
class QueueRepository {

    record Item(UUID id, UUID attemptId, UUID userId, UUID ownerOrgId, UUID gradingOrgId, String examCode, String reason, String state, UUID claimedBy, Instant claimedAt,
                Instant claimDueAt, int version, Instant createdAt) {}

    private static final String COLUMNS = "id, attempt_id, user_id, owner_org_id, grading_org_id, exam_code, reason, state, claimed_by, claimed_at, claim_due_at, version, created_at";

    private final JdbcTemplate jdbc;

    QueueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What the caller may see of one item: nothing (empty) for an item of another side or one somebody else holds. */
    Optional<Item> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from grading_queue_items where id = ?", (rs, i) -> row(rs), id).stream().findFirst();
    }

    /** The teacher's list: OPEN items of their side and the ones they hold, oldest first. */
    List<Item> forTeacher(UUID me, String exam) {
        return jdbc.query("select " + COLUMNS + " from grading_queue_items where grading_queue_my_side(exam_code, grading_org_id) and (state = 'OPEN' or claimed_by = ?) "
                + "and state <> 'DONE' and (?::text is null or exam_code = ?) and user_id <> ? order by created_at, id limit 500", (rs, i) -> row(rs), me, exam, exam, me);
    }

    /** The administrator's list: everything of their side, newest first. */
    List<Item> forAdmin(String exam, String state) {
        return jdbc.query("select " + COLUMNS + " from grading_queue_items where grading_queue_my_admin(grading_org_id) and (?::text is null or exam_code = ?) "
                + "and (?::text is null or state = ?) order by created_at desc, id limit 500", (rs, i) -> row(rs), exam, exam, state, state);
    }

    /** Optimistic claim: 1 when this call took the item, 0 when somebody else did (or it is not the caller's to take). */
    int claim(UUID id, int version, UUID me, Instant now, Instant due) {
        return jdbc.update("update grading_queue_items set state = 'CLAIMED', claimed_by = ?, claimed_at = ?, claim_due_at = ?, version = version + 1 "
                + "where id = ? and version = ? and state = 'OPEN' and user_id <> ?", me, Timestamp.from(now), Timestamp.from(due), id, version, me);
    }

    /** The holder puts the item back in the queue. */
    int release(UUID id, int version, UUID me) {
        return jdbc.update("update grading_queue_items set state = 'OPEN', claimed_by = null, claimed_at = null, claim_due_at = null, version = version + 1 "
                + "where id = ? and version = ? and state = 'CLAIMED' and claimed_by = ?", id, version, me);
    }

    /** The administrator hands the item to a teacher (who may not be the learner). */
    int reassign(UUID id, int version, UUID teacher, Instant now, Instant due) {
        return jdbc.update("update grading_queue_items set state = 'CLAIMED', claimed_by = ?, claimed_at = ?, claim_due_at = ?, version = version + 1 "
                + "where id = ? and version = ? and state in ('OPEN', 'CLAIMED') and user_id <> ?", teacher, Timestamp.from(now), Timestamp.from(due), id, version, teacher);
    }

    boolean isTeacherOfSide(Item item, UUID teacher) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select grading_queue_is_teacher(?, ?, ?)", Boolean.class, item.examCode(), item.gradingOrgId(), teacher));
    }

    private static Item row(ResultSet rs) throws SQLException {
        return new Item(rs.getObject("id", UUID.class), rs.getObject("attempt_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getObject("owner_org_id", UUID.class),
                rs.getObject("grading_org_id", UUID.class), rs.getString("exam_code"), rs.getString("reason"), rs.getString("state"), rs.getObject("claimed_by", UUID.class),
                instant(rs.getTimestamp("claimed_at")), instant(rs.getTimestamp("claim_due_at")), rs.getInt("version"), rs.getTimestamp("created_at").toInstant());
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
