package com.english9x.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fixtures of seat entitlements, written as the superuser: a seat entitlement needs a real seat of a real ACTIVE licence (P5.3: foreign key and guard). */
public final class SeatFixtures {

    private SeatFixtures() {}

    /**
     * An ACTIVE licence of {@code org} (valid around now and far into the future), a live seat of {@code user} and one entitlement of {@code course}
     * from {@code from} to {@code until} (not after the licence's end). The user becomes a member of the organisation. Returns the seat id.
     */
    public static UUID seatEntitlement(JdbcTemplate su, UUID org, UUID user, UUID course, Instant from, Instant until) {
        UUID license = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        su.update("insert into org_memberships(org_id,user_id) values (?,?) on conflict do nothing", org, user);
        su.update("insert into org_licenses(id,org_id,seats,months,price_vnd,activation,contract_no,status,valid_from,valid_until,created_by) "
                + "values (?,?,5,12,1000,'CONTRACT','FIXTURE','ACTIVE',now() - interval '1000 days','2099-01-01 00:00:00+00',?)", license, org, user);
        su.update("insert into org_license_courses(license_id,org_id,course_id) values (?,?,?)", license, org, course);
        su.update("insert into seat_assignments(id,license_id,org_id,user_id,assigned_by) values (?,?,?,?,?)", seat, license, org, user, user);
        su.update("insert into entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id) values (?,?,?,?,?,?)",
                user, course, Timestamp.from(from), Timestamp.from(until), seat, org);
        return seat;
    }
}
