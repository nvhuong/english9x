package com.english9x.classroom.klass;

import com.english9x.classroom.klass.ClassDtos.ClassMemberView;
import com.english9x.classroom.klass.ClassDtos.ClassView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Statements of the class endpoints; they run in the caller's transaction under its Row-Level Security context. */
@Repository
public class ClassRepository {

    private final JdbcTemplate jdbc;

    ClassRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> orgType(UUID orgId) {
        return jdbc.query("select type from organizations where id = ?", (rs, i) -> rs.getString(1), orgId).stream().findFirst();
    }

    boolean isTeacher(UUID orgId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from role_grants where org_id = ? and user_id = ? and role = 'TEACHER')", Boolean.class, orgId, userId));
    }

    UUID insert(UUID orgId, String name, UUID teacherId) {
        return jdbc.queryForObject("insert into classes(owner_org_id, name, teacher_id) values (?, ?, ?) returning id", UUID.class, orgId, name, teacherId);
    }

    private static ClassView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        int count = rs.getInt("member_count");
        boolean manage = rs.getBoolean("can_manage");
        return new ClassView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("kind"), rs.getObject("teacher_id", UUID.class), manage, manage ? count : null);
    }

    private static final String SELECT = "select c.id, c.name, c.kind, c.teacher_id, classroom_manages(c.id) as can_manage, "
            + "(select count(*) from class_members m where m.class_id = c.id) as member_count from classes c ";

    List<ClassView> list(UUID orgId) {
        return jdbc.query(SELECT + "where c.owner_org_id = ? order by lower(c.name), c.id", (rs, i) -> view(rs), orgId);
    }

    /** Public to the module: the assignments side asks the same question. Under RLS: a class of another organisation is not there. */
    public Optional<ClassView> find(UUID orgId, UUID classId) {
        return jdbc.query(SELECT + "where c.owner_org_id = ? and c.id = ?", (rs, i) -> view(rs), orgId, classId).stream().findFirst();
    }

    List<ClassMemberView> members(UUID classId) {
        return jdbc.query("select r_user_id, r_display_name, r_email, r_added_at from classroom_class_members(?)",
                (rs, i) -> new ClassMemberView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()), classId);
    }

    List<UUID> memberIds(UUID classId) {
        return jdbc.queryForList("select user_id from class_members where class_id = ? order by user_id", UUID.class, classId);
    }

    List<UUID> orgMembersAmong(UUID orgId, List<UUID> userIds) {
        return jdbc.query("select user_id from org_memberships where org_id = ? and user_id = any (?)", (rs, i) -> rs.getObject(1, UUID.class), orgId,
                userIds.toArray(new UUID[0]));
    }

    int addMembers(UUID classId, UUID orgId, List<UUID> userIds) {
        int n = 0;
        for (UUID u : userIds) {
            n += jdbc.update("insert into class_members(class_id, org_id, user_id) values (?, ?, ?) on conflict do nothing", classId, orgId, u);
        }
        return n;
    }

    boolean removeMember(UUID classId, UUID userId) {
        return jdbc.update("delete from class_members where class_id = ? and user_id = ?", classId, userId) > 0;
    }
}
