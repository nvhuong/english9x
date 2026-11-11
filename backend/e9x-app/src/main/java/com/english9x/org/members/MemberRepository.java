package com.english9x.org.members;

import com.english9x.org.members.MemberDtos.JoinCodeStatus;
import com.english9x.org.members.MemberDtos.JoinCodeView;
import java.sql.Array;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Statements of the member endpoints; they run in the caller's transaction under its Row-Level Security context. */
@Repository
class MemberRepository {

    private final JdbcTemplate jdbc;

    MemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    boolean orgExists(UUID orgId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from organizations where id = ?)", Boolean.class, orgId));
    }

    /** Under RLS: only classes of the current organisation are visible. */
    boolean classInOrg(UUID classId, UUID orgId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from classes where id = ? and owner_org_id = ?)", Boolean.class, classId, orgId));
    }

    Map<String, UUID> classesByName(UUID orgId) {
        Map<String, UUID> out = new java.util.HashMap<>();
        jdbc.query("select id, name from classes where owner_org_id = ?", rs -> {
            out.putIfAbsent(rs.getString("name").strip().toLowerCase(java.util.Locale.ROOT), rs.getObject("id", UUID.class));
        }, orgId);
        return out;
    }

    // ---- join codes ----------------------------------------------------------------------------------------------------------

    /** @return the id, or empty when the digest collides with another code (retry with a new code) */
    Optional<UUID> insertCode(UUID orgId, UUID classId, byte[] hash, UUID by, Instant expires, int maxUses) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "insert into join_codes(org_id, class_id, code_hash, created_by, expires_at, max_uses) values (?, ?, ?, ?, ?, ?) returning id",
                    UUID.class, orgId, classId, hash, by, ts(expires), maxUses));
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    private static final String VIEW = """
            select id, class_id, created_at, expires_at, max_uses, used_count,
                   case when revoked_at is not null then 'REVOKED'
                        when expires_at <= ? then 'EXPIRED'
                        when max_uses is not null and used_count >= max_uses then 'EXHAUSTED'
                        else 'ACTIVE' end as status
              from join_codes""";

    private static JoinCodeView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new JoinCodeView(rs.getObject("id", UUID.class), rs.getObject("class_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), rs.getInt("max_uses"), rs.getInt("used_count"), JoinCodeStatus.valueOf(rs.getString("status")));
    }

    Optional<JoinCodeView> findCode(UUID orgId, UUID id, Instant now) {
        return jdbc.query(VIEW + " where org_id = ? and id = ?", (rs, n) -> view(rs), ts(now), orgId, id).stream().findFirst();
    }

    List<JoinCodeView> codes(UUID orgId, Instant now) {
        return jdbc.query(VIEW + " where org_id = ? order by created_at desc, id limit 200", (rs, n) -> view(rs), ts(now), orgId);
    }

    /** Revokes a code not yet revoked; empty when there is none to revoke. */
    Optional<JoinCodeView> revokeCode(UUID orgId, UUID id, Instant now) {
        int n = jdbc.update("update join_codes set revoked_at = ? where org_id = ? and id = ? and revoked_at is null", ts(now), orgId, id);
        return n == 0 ? Optional.empty() : findCode(orgId, id, now);
    }

    record Redeemed(String outcome, UUID orgId, UUID classId, UUID codeId) {}

    Redeemed redeem(byte[] hash, Instant now) {
        return jdbc.queryForObject("select * from org_join_code_redeem(?, ?)",
                (rs, n) -> new Redeemed(rs.getString("r_outcome"), rs.getObject("r_org_id", UUID.class), rs.getObject("r_class_id", UUID.class),
                        rs.getObject("r_code_id", UUID.class)), hash, ts(now));
    }

    // ---- removal -------------------------------------------------------------------------------------------------------------

    record Removal(String outcome, List<UUID> seatIds, List<UUID> classIds, List<String> roles) {}

    Removal remove(UUID orgId, UUID userId, Instant now) {
        return jdbc.queryForObject("select * from members_remove(?, ?, ?)", (rs, n) -> new Removal(rs.getString("r_outcome"),
                uuids(rs.getArray("r_seat_ids")), uuids(rs.getArray("r_class_ids")), strings(rs.getArray("r_roles"))), orgId, userId, ts(now));
    }

    private static List<UUID> uuids(Array a) throws java.sql.SQLException {
        return a == null ? List.of() : java.util.Arrays.stream((Object[]) a.getArray()).map(o -> UUID.fromString(o.toString())).toList();
    }

    private static List<String> strings(Array a) throws java.sql.SQLException {
        return a == null ? List.of() : java.util.Arrays.stream((Object[]) a.getArray()).map(Object::toString).toList();
    }
}
