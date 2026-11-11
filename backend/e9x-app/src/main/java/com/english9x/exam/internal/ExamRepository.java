package com.english9x.exam.internal;

import com.english9x.domain.exam.AttemptStatus;
import com.english9x.domain.exam.ExamAttempt;
import com.english9x.domain.exam.SectionPlan;
import com.english9x.domain.exam.SubmitReason;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL of the exam module. Everything runs on the application role: Row-Level Security (user_id = the person) answers for who sees what. */
@Repository
class ExamRepository {

    /** The persisted attempt: the domain aggregate plus the columns the domain does not know. */
    record Loaded(UUID id, UUID userId, UUID orgId, UUID testVersionId, UUID courseId, String examCode, Instant startedAt, long version, ExamAttempt attempt,
                  List<String> skills, boolean wasSubmitted) {}

    record SectionRow(int position, String code, String skill, int durationSec, Instant startsAt, Instant endsAt) {}

    /** Thrown when the optimistic lock lost: somebody changed the attempt between the load and the save. */
    static final class Conflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Conflict() {
            super("attempt changed concurrently", null, false, false);
        }
    }

    private final JdbcTemplate jdbc;

    ExamRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant ts(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    Optional<Loaded> load(UUID attemptId, UUID userId) {
        List<Object[]> heads = jdbc.query("select id, user_id, owner_org_id, test_version_id, course_id, exam_code, started_at, version, status, max_resumes, resume_count, "
                        + "interrupted, submit_reason, current_section_index, submitted_at from exam_attempts where id = ? and user_id = ?",
                (rs, i) -> new Object[] {rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getObject("owner_org_id", UUID.class),
                        rs.getObject("test_version_id", UUID.class), rs.getObject("course_id", UUID.class), rs.getString("exam_code"), ts(rs, "started_at"),
                        rs.getLong("version"), rs.getString("status"), rs.getInt("max_resumes"), rs.getInt("resume_count"), rs.getBoolean("interrupted"),
                        rs.getString("submit_reason"), rs.getInt("current_section_index"), ts(rs, "submitted_at")}, attemptId, userId);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        Object[] h = heads.get(0);
        List<SectionRow> rows = sections(attemptId);
        if (rows.isEmpty()) {
            return Optional.empty(); // not an attempt of the exam room (no sections)
        }
        List<SectionPlan> plans = new ArrayList<>();
        List<Instant> starts = new ArrayList<>();
        List<Instant> ends = new ArrayList<>();
        List<String> skills = new ArrayList<>();
        for (SectionRow r : rows) {
            plans.add(new SectionPlan(r.code(), Duration.ofSeconds(r.durationSec())));
            starts.add(r.startsAt());
            ends.add(r.endsAt());
            skills.add(r.skill());
        }
        AttemptStatus status = AttemptStatus.valueOf((String) h[8]);
        SubmitReason reason = h[12] == null ? null : SubmitReason.valueOf((String) h[12]);
        ExamAttempt attempt = ExamAttempt.restore(h[0].toString(), plans, (Integer) h[9], (Integer) h[13], starts, ends, (Integer) h[10], (Boolean) h[11], status,
                reason, (Instant) h[14]);
        return Optional.of(new Loaded((UUID) h[0], (UUID) h[1], (UUID) h[2], (UUID) h[3], (UUID) h[4], (String) h[5], (Instant) h[6], (Long) h[7], attempt, skills, status == AttemptStatus.SUBMITTED));
    }

    List<SectionRow> sections(UUID attemptId) {
        return jdbc.query("select position, section_code, skill, duration_sec, starts_at, ends_at from attempt_sections where attempt_id = ? order by position",
                (rs, i) -> new SectionRow(rs.getInt("position"), rs.getString("section_code"), rs.getString("skill"), rs.getInt("duration_sec"), ts(rs, "starts_at"),
                        ts(rs, "ends_at")), attemptId);
    }

    /** Writes the aggregate back if nobody changed the row since it was loaded; {@link Conflict} otherwise. */
    void save(Loaded l) {
        ExamAttempt a = l.attempt();
        int index = a.sectionIndex();
        Instant deadline = a.status() == AttemptStatus.SUBMITTED ? null : a.deadline();
        int n = jdbc.update("update exam_attempts set status = ?, submitted_at = ?, submit_reason = ?, resume_count = ?, interrupted = ?, current_section_index = ?, "
                        + "current_section_started_at = ?, deadline_at = ?, version = version + 1 where id = ? and version = ?",
                a.status().name(), ts(a.submittedAt().orElse(null)), a.submitReason().map(Enum::name).orElse(null), a.resumeCount(), a.interrupted(), index,
                ts(a.sectionStartedAt(index)), ts(deadline), l.id(), l.version());
        if (n != 1) {
            throw new Conflict();
        }
        for (int i = 0; i < a.sectionCount(); i++) {
            jdbc.update("update attempt_sections set starts_at = ?, ends_at = ? where attempt_id = ? and position = ?", ts(a.sectionStartedAt(i)), ts(a.sectionEndedAt(i)),
                    l.id(), i);
        }
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    Optional<UUID> inProgress(UUID userId, UUID testVersionId) {
        return jdbc.queryForList("select id from exam_attempts where user_id = ? and test_version_id = ? and status = 'IN_PROGRESS'", UUID.class, userId, testVersionId).stream()
                .findFirst();
    }

    /** Courses of this exam in which the person holds an entitlement of any period; the caller asks the gate which one is active. */
    List<UUID> entitledCourses(UUID userId, String examCode) {
        return jdbc.queryForList("select distinct c.id from courses c join entitlements e on e.course_id = c.id where e.user_id = ? and c.exam_code = ?", UUID.class,
                userId, examCode);
    }

    record NewAttempt(UUID id, UUID userId, UUID orgId, UUID testVersionId, UUID courseId, String examCode, int maxResumes, Instant now, ExamAttempt attempt,
                      List<String> skills) {}

    record QuestionRef(UUID questionId, UUID questionVersionId, String sectionCode) {}

    void insert(NewAttempt n, List<QuestionRef> questions) {
        ExamAttempt a = n.attempt();
        jdbc.update("insert into exam_attempts(id, user_id, owner_org_id, test_version_id, status, started_at, exam_code, course_id, max_resumes, "
                        + "current_section_index, current_section_started_at, deadline_at) values (?,?,?,?,'IN_PROGRESS',?,?,?,?,0,?,?)",
                n.id(), n.userId(), n.orgId(), n.testVersionId(), ts(n.now()), n.examCode(), n.courseId(), n.maxResumes(), ts(n.now()), ts(a.deadline()));
        for (int i = 0; i < a.sectionCount(); i++) {
            jdbc.update("insert into attempt_sections(attempt_id, position, user_id, section_code, skill, duration_sec, starts_at) values (?,?,?,?,?,?,?)", n.id(), i,
                    n.userId(), a.section(i).id(), n.skills().get(i), a.section(i).duration().toSeconds(), ts(a.sectionStartedAt(i)));
        }
        jdbc.batchUpdate("insert into attempt_questions(attempt_id, question_id, user_id, question_version_id, section_code) values (?,?,?,?,?) on conflict do nothing",
                questions, 500, (ps, q) -> {
                    ps.setObject(1, n.id());
                    ps.setObject(2, q.questionId());
                    ps.setObject(3, n.userId());
                    ps.setObject(4, q.questionVersionId());
                    ps.setString(5, q.sectionCode());
                });
    }

    Optional<String> sectionOfQuestion(UUID attemptId, UUID questionId) {
        return jdbc.queryForList("select section_code from attempt_questions where attempt_id = ? and question_id = ?", String.class, attemptId, questionId).stream().findFirst();
    }

    /** Upsert by (attempt, question); the row with the newer answeredAt wins (an equal one replaces: a re-send is idempotent). */
    void upsertResponse(UUID attemptId, UUID questionId, UUID userId, JsonNode answer, Instant answeredAt, Instant now) {
        jdbc.update("insert into responses(attempt_id, question_id, user_id, answer, answered_at, saved_at) values (?,?,?,?::jsonb,?,?) "
                        + "on conflict (attempt_id, question_id) do update set answer = excluded.answer, answered_at = excluded.answered_at, saved_at = excluded.saved_at "
                        + "where responses.answered_at <= excluded.answered_at",
                attemptId, questionId, userId, answer.toString(), ts(answeredAt), ts(now));
    }

    record Saved(UUID questionId, JsonNode value, Instant savedAt) {}

    List<Saved> savedOfSection(UUID attemptId, String sectionCode, com.fasterxml.jackson.databind.ObjectMapper json) {
        return jdbc.query("select r.question_id, r.answer::text as a, r.answered_at from responses r join attempt_questions q on q.attempt_id = r.attempt_id and q.question_id = r.question_id "
                        + "where r.attempt_id = ? and q.section_code = ?",
                (rs, i) -> {
                    try {
                        return new Saved(rs.getObject("question_id", UUID.class), json.readTree(rs.getString("a")), ts(rs, "answered_at"));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                }, attemptId, sectionCode);
    }

    /** True the first time; false when this recording was already given out. */
    boolean markAudioPlayed(UUID attemptId, UUID mediaId, UUID userId, Instant now) {
        return jdbc.update("insert into audio_plays(attempt_id, media_id, user_id, played_at) values (?,?,?,?) on conflict do nothing", attemptId, mediaId, userId, ts(now)) == 1;
    }

    void upsertRecording(UUID attemptId, UUID questionId, UUID userId, String key, String mime, long size, Instant now) {
        jdbc.update("insert into speaking_recordings(attempt_id, question_id, user_id, storage_path, mime_type, size_bytes, uploaded_at) values (?,?,?,?,?,?,?) "
                        + "on conflict (attempt_id, question_id) do update set storage_path = excluded.storage_path, mime_type = excluded.mime_type, size_bytes = excluded.size_bytes, "
                        + "uploaded_at = excluded.uploaded_at",
                attemptId, questionId, userId, key, mime, size, ts(now));
    }
}
