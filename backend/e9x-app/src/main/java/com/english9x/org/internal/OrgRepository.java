package com.english9x.org.internal;

import com.english9x.org.api.Role;
import com.english9x.org.internal.OrgDtos.AdminOrgView;
import com.english9x.org.internal.OrgDtos.GrantView;
import com.english9x.org.internal.OrgDtos.InvitationStatus;
import com.english9x.org.internal.OrgDtos.InvitationView;
import com.english9x.org.internal.OrgDtos.MemberView;
import com.english9x.org.internal.OrgDtos.OrgType;
import com.english9x.org.internal.OrgDtos.OrgView;
import com.english9x.org.internal.OrgDtos.RoleSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Every statement of the org module. All of them run in a transaction under the acting user's Row-Level Security
 * context, so what comes back is what that user may see and a write the policies refuse fails, whatever this code
 * intends. The two definer functions (accept an invitation, redeem a join code) are the only way around RLS.
 */
@Repository
class OrgRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    OrgRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant instant(java.sql.Timestamp t) {
        return t.toInstant();
    }

    // ---- organisations -----------------------------------------------------------------------------------------------------

    Optional<OrgView> findOrg(UUID id) {
        return jdbc.query("select id, name, type from organizations where id = ?",
                (rs, n) -> new OrgView(rs.getObject("id", UUID.class), rs.getString("name"), OrgType.valueOf(rs.getString("type"))), id)
                .stream().findFirst();
    }

    AdminOrgView insertOrg(String name, OrgType type) {
        return jdbc.queryForObject("insert into organizations(name, type) values (?, ?) returning id, name, type, created_at",
                (rs, n) -> new AdminOrgView(rs.getObject("id", UUID.class), rs.getString("name"), OrgType.valueOf(rs.getString("type")),
                        instant(rs.getTimestamp("created_at"))),
                name, type.name());
    }

    List<AdminOrgView> listAllOrgs() {
        return jdbc.query("select id, name, type, created_at from organizations order by created_at desc, name",
                (rs, n) -> new AdminOrgView(rs.getObject("id", UUID.class), rs.getString("name"), OrgType.valueOf(rs.getString("type")),
                        instant(rs.getTimestamp("created_at"))));
    }

    List<OrgView> orgsOf(UUID userId) {
        return jdbc.query("""
                select o.id, o.name, o.type from organizations o
                  join org_memberships m on m.org_id = o.id
                 where m.user_id = ? order by o.name, o.id""",
                (rs, n) -> new OrgView(rs.getObject("id", UUID.class), rs.getString("name"), OrgType.valueOf(rs.getString("type"))), userId);
    }

    List<GrantView> grantsOf(UUID userId) {
        return jdbc.query("select role, org_id, exam_code from role_grants where user_id = ? order by role, org_id, exam_code",
                (rs, n) -> new GrantView(Role.valueOf(rs.getString("role")), rs.getObject("org_id", UUID.class), rs.getString("exam_code")), userId);
    }

    // ---- members and their grants ------------------------------------------------------------------------------------------

    List<MemberView> members(UUID orgId) {
        Map<UUID, List<RoleSpec>> roles = new LinkedHashMap<>();
        jdbc.query("select user_id, role, exam_code from role_grants where org_id = ? order by role, exam_code", rs -> {
            roles.computeIfAbsent(rs.getObject("user_id", UUID.class), k -> new ArrayList<>())
                    .add(new RoleSpec(Role.valueOf(rs.getString("role")), rs.getString("exam_code")));
        }, orgId);
        return jdbc.query("""
                select m.user_id, u.email, u.display_name
                  from org_memberships m join users u on u.id = m.user_id
                 where m.org_id = ? order by lower(coalesce(u.display_name, u.email)), u.email""",
                (rs, n) -> {
                    UUID id = rs.getObject("user_id", UUID.class);
                    return new MemberView(id, rs.getString("email"), rs.getString("display_name"), roles.getOrDefault(id, List.of()));
                }, orgId);
    }

    boolean isMember(UUID orgId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from org_memberships where org_id = ? and user_id = ?)", Boolean.class, orgId, userId));
    }

    List<RoleSpec> rolesOf(UUID orgId, UUID userId) {
        return jdbc.query("select role, exam_code from role_grants where org_id = ? and user_id = ? order by role, exam_code",
                (rs, n) -> new RoleSpec(Role.valueOf(rs.getString("role")), rs.getString("exam_code")), orgId, userId);
    }

    /** Serialises the writers of one organisation's grants (the last-admin rule is a check-then-write). */
    void lockOrg(UUID orgId) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 7))", orgId.toString());
    }

    long otherAdmins(UUID orgId, UUID except) {
        Long n = jdbc.queryForObject("select count(*) from role_grants where org_id = ? and role = 'ORG_ADMIN' and user_id <> ?", Long.class, orgId, except);
        return n == null ? 0 : n;
    }

    /** Insert the new grants first, delete the obsolete ones in one statement last: an admin editing themselves keeps the power until the end. */
    void setRoles(UUID orgId, UUID userId, List<RoleSpec> wanted) {
        List<RoleSpec> have = rolesOf(orgId, userId);
        for (RoleSpec w : wanted) {
            if (!have.contains(w)) {
                jdbc.update("insert into role_grants(user_id, org_id, exam_code, role) values (?, ?, ?, ?)", userId, orgId, w.examCode(), w.role().name());
            }
        }
        List<Object[]> obsolete = new ArrayList<>();
        for (RoleSpec h : have) {
            if (!wanted.contains(h)) {
                obsolete.add(new Object[] {h.role().name(), h.examCode()});
            }
        }
        if (!obsolete.isEmpty()) {
            StringBuilder sql = new StringBuilder("delete from role_grants where org_id = ? and user_id = ? and (");
            List<Object> args = new ArrayList<>(List.of(orgId, userId));
            for (int i = 0; i < obsolete.size(); i++) {
                sql.append(i == 0 ? "" : " or ").append("(role = ? and exam_code is not distinct from ?)");
                args.add(obsolete.get(i)[0]);
                args.add(obsolete.get(i)[1]);
            }
            sql.append(")");
            jdbc.update(sql.toString(), args.toArray());
        }
    }

    // ---- invitations -------------------------------------------------------------------------------------------------------

    long invitationsSentSince(UUID inviter, Instant since) {
        Long n = jdbc.queryForObject("select count(*) from invitations where invited_by = ? and created_at > ?", Long.class, inviter, ts(since));
        return n == null ? 0 : n;
    }

    void lockInviter(UUID inviter) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 11))", inviter.toString());
    }

    void retirePending(UUID orgId, String email, Instant now) {
        jdbc.update("update invitations set revoked_at = ? where org_id is not distinct from ? and email = lower(?) and accepted_at is null and revoked_at is null",
                ts(now), orgId, email);
    }

    UUID insertInvitation(UUID orgId, String email, List<RoleSpec> roles, byte[] tokenHash, UUID invitedBy, Instant expires) {
        List<Map<String, Object>> grants = new ArrayList<>();
        for (RoleSpec r : roles) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("role", r.role().name());
            g.put("exam", r.examCode());
            grants.add(g);
        }
        String json;
        try {
            json = JSON.writeValueAsString(grants);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return jdbc.queryForObject(
                "insert into invitations(org_id, email, grants, token_hash, invited_by, expires_at) values (?, lower(?), ?::jsonb, ?, ?, ?) returning id",
                UUID.class, orgId, email, json, tokenHash, invitedBy, ts(expires));
    }

    /** Newest first, at most 100. {@code orgId} null = the English9x (internal) invitations. */
    List<InvitationView> invitations(UUID orgId, Instant now) {
        return jdbc.query("""
                select id, email, grants::text as grants, created_at, expires_at,
                       case when accepted_at is not null then 'ACCEPTED'
                            when revoked_at is not null then 'REVOKED'
                            when expires_at <= ? then 'EXPIRED' else 'PENDING' end as status
                  from invitations where org_id is not distinct from ?
                 order by created_at desc limit 100""",
                (rs, n) -> new InvitationView(rs.getObject("id", UUID.class), rs.getString("email"), parseGrants(rs.getString("grants")),
                        InvitationStatus.valueOf(rs.getString("status")), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("expires_at"))),
                ts(now), orgId);
    }

    private static List<RoleSpec> parseGrants(String json) {
        try {
            List<RoleSpec> out = new ArrayList<>();
            for (JsonNode g : JSON.readTree(json)) {
                out.add(new RoleSpec(Role.valueOf(g.path("role").asText()), g.path("exam").isNull() ? null : g.path("exam").asText()));
            }
            return out;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Revokes a pending invitation of that scope; false when there is none (or it is not the caller's to see). */
    boolean revoke(UUID invitationId, UUID orgId, Instant now) {
        return jdbc.update("update invitations set revoked_at = ? where id = ? and org_id is not distinct from ? and accepted_at is null and revoked_at is null",
                ts(now), invitationId, orgId) == 1;
    }

    record Accepted(String outcome, UUID orgId) {}

    Accepted accept(byte[] tokenHash, Instant now) {
        return jdbc.queryForObject("select * from org_invitation_accept(?, ?)",
                (rs, n) -> new Accepted(rs.getString("r_outcome"), rs.getObject("r_org_id", UUID.class)), tokenHash, ts(now));
    }
}
