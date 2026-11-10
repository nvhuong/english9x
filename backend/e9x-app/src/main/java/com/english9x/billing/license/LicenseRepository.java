package com.english9x.billing.license;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Licences, their courses and the seats. Every statement runs under the caller's Row-Level Security, and the database refuses what the service forgot. */
@Repository
class LicenseRepository {

    record License(UUID id, UUID orgId, int seats, int months, long priceVnd, String activation, String contractNo, String status, Instant validFrom, Instant validUntil,
                   Instant createdAt) {}

    record Course(UUID id, String code, String title) {}

    record Seat(UUID id, UUID licenseId, UUID userId, Instant assignedAt) {}

    private static final String LICENSE_COLUMNS = "id, org_id, seats, months, price_vnd, activation, contract_no, status, valid_from, valid_until, created_at";

    private final JdbcTemplate jdbc;

    LicenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean orgExists(UUID orgId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from organizations where id = ?)", Boolean.class, orgId));
    }

    /** The courses among {@code ids} a licence may cover: English9x's own and PUBLISHED, in the order of {@code ids}. */
    List<Course> licensableCourses(List<UUID> ids) {
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Course> found = jdbc.query("select id, code, title from courses where id in (" + marks + ") and owner_org_id is null and status = 'PUBLISHED'",
                (rs, i) -> new Course(rs.getObject("id", UUID.class), rs.getString("code").strip(), rs.getString("title")), ids.toArray());
        List<Course> ordered = new ArrayList<>();
        for (UUID id : ids) {
            found.stream().filter(c -> c.id().equals(id)).findFirst().ifPresent(ordered::add);
        }
        return ordered;
    }

    /** Every course a licence may cover, by code. */
    List<Course> allLicensableCourses() {
        return jdbc.query("select id, code, title from courses where owner_org_id is null and status = 'PUBLISHED' order by code",
                (rs, i) -> new Course(rs.getObject(1, UUID.class), rs.getString(2).strip(), rs.getString(3)));
    }

    /** The oldest ORG_ADMIN of the organisation: the person an order of a QR licence is filed for. */
    Optional<UUID> defaultPayer(UUID orgId) {
        return jdbc.query("select g.user_id from role_grants g join users u on u.id = g.user_id where g.role = 'ORG_ADMIN' and g.org_id = ? order by u.created_at, u.id limit 1",
                (rs, i) -> rs.getObject(1, UUID.class), orgId).stream().findFirst();
    }

    boolean isOrgAdmin(UUID orgId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from role_grants where role = 'ORG_ADMIN' and org_id = ? and user_id = ?)", Boolean.class, orgId, userId));
    }

    void insertLicense(UUID id, UUID orgId, int seats, int months, long priceVnd, String activation, String contractNo, String status, Instant validFrom, Instant validUntil,
                       Instant createdAt, UUID createdBy) {
        jdbc.update("insert into org_licenses(id, org_id, seats, months, price_vnd, activation, contract_no, status, valid_from, valid_until, created_at, created_by) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, orgId, seats, months, priceVnd, activation, contractNo, status, ts(validFrom), ts(validUntil), Timestamp.from(createdAt), createdBy);
    }

    void insertLicenseCourse(UUID licenseId, UUID orgId, UUID courseId) {
        jdbc.update("insert into org_license_courses(license_id, org_id, course_id) values (?, ?, ?)", licenseId, orgId, courseId);
    }

    Optional<License> find(UUID id) {
        return jdbc.query("select " + LICENSE_COLUMNS + " from org_licenses where id = ?", (rs, i) -> license(rs), id).stream().findFirst();
    }

    List<License> ofOrg(UUID orgId) {
        return jdbc.query("select " + LICENSE_COLUMNS + " from org_licenses where org_id = ? order by created_at desc, id", (rs, i) -> license(rs), orgId);
    }

    List<License> all(int limit) {
        return jdbc.query("select " + LICENSE_COLUMNS + " from org_licenses order by created_at desc, id limit ?", (rs, i) -> license(rs), limit);
    }

    Map<UUID, List<Course>> coursesOf(List<UUID> licenseIds) {
        Map<UUID, List<Course>> out = new LinkedHashMap<>();
        if (licenseIds.isEmpty()) {
            return out;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement("select lc.license_id, c.id, c.code, c.title from org_license_courses lc join courses c on c.id = lc.course_id where lc.license_id = any(?) order by c.code");
            ps.setArray(1, con.createArrayOf("uuid", licenseIds.toArray()));
            return ps;
        }, rs -> {
            out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(new Course(rs.getObject(2, UUID.class), rs.getString(3).strip(), rs.getString(4)));
        });
        return out;
    }

    /** The order code of each licence that has one. */
    Map<UUID, String> orderCodes(List<UUID> licenseIds) {
        Map<UUID, String> out = new LinkedHashMap<>();
        if (licenseIds.isEmpty()) {
            return out;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement("select license_id, code from orders where license_id = any(?)");
            ps.setArray(1, con.createArrayOf("uuid", licenseIds.toArray()));
            return ps;
        }, rs -> {
            out.put(rs.getObject(1, UUID.class), rs.getString(2).strip());
        });
        return out;
    }

    /** PENDING_PAYMENT to ACTIVE; false when it was not pending. */
    boolean activate(UUID id, Instant from, Instant until) {
        return jdbc.update("update org_licenses set status = 'ACTIVE', valid_from = ?, valid_until = ? where id = ? and status = 'PENDING_PAYMENT'", Timestamp.from(from), Timestamp.from(until), id) == 1;
    }

    boolean cancel(UUID id) {
        return jdbc.update("update org_licenses set status = 'CANCELLED' where id = ? and status = 'PENDING_PAYMENT'", id) == 1;
    }

    // ---- seats ----------------------------------------------------------------------------------------------------------------

    /** The people holding a live seat of the licence, oldest first. */
    List<Seat> liveSeats(UUID licenseId) {
        return jdbc.query("select id, license_id, user_id, assigned_at from seat_assignments where license_id = ? and revoked_at is null order by assigned_at, id",
                (rs, i) -> new Seat(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getTimestamp(4).toInstant()), licenseId);
    }

    Optional<Seat> liveSeat(UUID licenseId, UUID userId) {
        return jdbc.query("select id, license_id, user_id, assigned_at from seat_assignments where license_id = ? and user_id = ? and revoked_at is null",
                (rs, i) -> new Seat(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getTimestamp(4).toInstant()), licenseId, userId).stream().findFirst();
    }

    /** Course ids each live seat holds (the entitlements that are still open). */
    Map<UUID, List<UUID>> seatCourses(UUID licenseId) {
        Map<UUID, List<UUID>> out = new LinkedHashMap<>();
        jdbc.query("select e.source_seat_id, e.course_id from entitlements e join seat_assignments s on s.id = e.source_seat_id where s.license_id = ? and s.revoked_at is null order by e.course_id",
                rs -> {
                    out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getObject(2, UUID.class));
                }, licenseId);
        return out;
    }

    /** Whether the user is a member of the organisation (visible to its ORG_ADMIN under RLS). */
    boolean isMember(UUID orgId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from org_memberships where org_id = ? and user_id = ?)", Boolean.class, orgId, userId));
    }

    void insertSeat(UUID id, UUID licenseId, UUID orgId, UUID userId, Instant at, UUID by) {
        jdbc.update("insert into seat_assignments(id, license_id, org_id, user_id, assigned_at, assigned_by) values (?,?,?,?,?,?)", id, licenseId, orgId, userId, Timestamp.from(at), by);
    }

    void insertSeatEntitlement(UUID userId, UUID courseId, Instant from, Instant until, UUID seatId, UUID orgId) {
        jdbc.update("insert into entitlements(user_id, course_id, valid_from, valid_until, source_seat_id, source_org_id) values (?,?,?,?,?,?)",
                userId, courseId, Timestamp.from(from), Timestamp.from(until), seatId, orgId);
    }

    /** Sets revoked_at; the database ends the seat's entitlements in the same statement. False when it was already revoked. */
    boolean revokeSeat(UUID seatId, Instant at) {
        return jdbc.update("update seat_assignments set revoked_at = ? where id = ? and revoked_at is null", Timestamp.from(at), seatId) == 1;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static License license(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp from = rs.getTimestamp("valid_from");
        Timestamp until = rs.getTimestamp("valid_until");
        return new License(rs.getObject("id", UUID.class), rs.getObject("org_id", UUID.class), rs.getInt("seats"), rs.getInt("months"), rs.getLong("price_vnd"), rs.getString("activation"),
                rs.getString("contract_no"), rs.getString("status"), from == null ? null : from.toInstant(), until == null ? null : until.toInstant(), rs.getTimestamp("created_at").toInstant());
    }
}
