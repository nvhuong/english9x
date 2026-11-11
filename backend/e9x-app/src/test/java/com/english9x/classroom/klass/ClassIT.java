package com.english9x.classroom.klass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.OrgIT;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** F45: classes (centre) and groups (enterprise), the roster, courses for the whole class through the seat path. */
class ClassIT extends OrgIT {

    private String classes(UUID org) {
        return "/api/orgs/" + org + "/classes";
    }

    private UUID teacher(UUID org) {
        UUID t = user("teacher");
        grant(t, org, "IELTS", "TEACHER");
        return t;
    }

    private UUID learner(UUID org) {
        UUID l = user("learner");
        grant(l, org, null, "LEARNER");
        return l;
    }

    private UUID course() {
        UUID id = UUID.randomUUID();
        su.update("insert into courses(id,owner_org_id,exam_code,code,title,status) values (?,null,'IELTS',?,'Course','PUBLISHED')", id, id.toString().replace("-", "").substring(0, 6).toUpperCase());
        return id;
    }

    private UUID licence(UUID org, UUID by, int seats, UUID course) {
        UUID license = UUID.randomUUID();
        su.update("insert into org_licenses(id,org_id,seats,months,price_vnd,activation,contract_no,status,valid_from,valid_until,created_by) "
                + "values (?,?,?,12,1000,'CONTRACT','C-1','ACTIVE',now() - interval '1 day','2099-01-01 00:00:00+00',?)", license, org, seats, by);
        su.update("insert into org_license_courses(license_id,org_id,course_id) values (?,?,?)", license, org, course);
        return license;
    }

    @Test
    void enterpriseGroupHasNoTeacherRequirement() {
        UUID org = org("ENTERPRISE");
        UUID admin = orgAdmin(org);
        Res r = post(classes(org), Map.of("name", "Phong Kinh doanh"), admin, org);
        assertEquals(201, r.status(), r.raw());
        assertEquals("GROUP", r.body().path("kind").asText());
        assertTrue(r.body().path("teacherId").isNull(), r.raw());
        assertEquals(1, su.queryForObject("select count(*) from audit_log where action = 'class.create' and target_id = ?", Integer.class, r.body().path("id").asText()));

        // an enterprise has no teachers: naming one is refused
        UUID someone = learner(org);
        Res withTeacher = post(classes(org), Map.of("name", "Nhom 2", "teacherId", someone), admin, org);
        assertEquals(422, withTeacher.status(), withTeacher.raw());
        assertEquals("urn:e9x:problem:group-has-no-teacher", withTeacher.problemType());
    }

    @Test
    void aCentreClassNeedsARealTeacher() {
        UUID org = org("CENTER");
        UUID admin = orgAdmin(org);
        UUID teacher = teacher(org);
        UUID learner = learner(org);
        assertEquals("urn:e9x:problem:teacher-required", post(classes(org), Map.of("name", "IELTS 6.5"), admin, org).problemType());
        assertEquals("urn:e9x:problem:not-a-teacher", post(classes(org), Map.of("name", "IELTS 6.5", "teacherId", learner), admin, org).problemType());
        Res ok = post(classes(org), Map.of("name", "IELTS 6.5", "teacherId", teacher), admin, org);
        assertEquals(201, ok.status(), ok.raw());
        assertEquals("CLASS", ok.body().path("kind").asText());
        assertEquals(teacher.toString(), ok.body().path("teacherId").asText());
        assertEquals(0, ok.body().path("memberCount").asInt());
    }

    @Test
    void whoMayWhat() {
        UUID org = org("CENTER");
        UUID admin = orgAdmin(org);
        UUID teacher = teacher(org);
        UUID otherTeacher = teacher(org);
        UUID learner = learner(org);
        UUID other = learner(org);
        UUID cls = UUID.fromString(post(classes(org), Map.of("name", "A", "teacherId", teacher), admin, org).body().path("id").asText());
        String members = classes(org) + "/" + cls + "/members";

        // a learner creates nothing and manages nothing; the other teacher does not manage this class
        assertEquals(403, post(classes(org), Map.of("name", "Hack", "teacherId", teacher), learner, org).status());
        assertEquals(403, post(classes(org), Map.of("name", "Hack", "teacherId", teacher), teacher, org).status(), "a teacher does not create classes");
        assertEquals(403, post(members, Map.of("userIds", List.of(learner)), learner, org).status());
        assertEquals(403, post(members, Map.of("userIds", List.of(learner)), otherTeacher, org).status());

        // the class's teacher and the admin manage the roster
        Res added = post(members, Map.of("userIds", List.of(learner)), teacher, org);
        assertEquals(200, added.status(), added.raw());
        assertEquals(1, added.body().path("added").asInt());
        assertEquals(0, post(members, Map.of("userIds", List.of(learner)), admin, org).body().path("added").asInt(), "adding twice changes nothing");
        assertEquals(1, post(members, Map.of("userIds", List.of(other)), admin, org).body().path("added").asInt());

        // somebody outside the organisation cannot join the class
        UUID outsider = user("outsider");
        Res refused = post(members, Map.of("userIds", List.of(outsider)), admin, org);
        assertEquals(422, refused.status(), refused.raw());
        assertEquals("urn:e9x:problem:not-an-org-member", refused.problemType());

        // the roster: the teacher sees names, not addresses; the admin sees addresses; a learner has no roster
        Res forTeacher = get(members, teacher, org);
        assertEquals(200, forTeacher.status(), forTeacher.raw());
        assertEquals(2, forTeacher.body().size());
        assertTrue(forTeacher.body().get(0).path("email").isNull(), forTeacher.raw());
        assertTrue(get(members, admin, org).body().get(0).path("email").asText().endsWith("@example.test"));
        assertEquals(403, get(members, learner, org).status());

        // the class list is the organisation's; counts only for those who manage it
        Res asLearner = get(classes(org), learner, org);
        assertEquals(200, asLearner.status());
        assertTrue(asLearner.body().get(0).path("memberCount").isNull() && !asLearner.body().get(0).path("canManage").asBoolean(), asLearner.raw());
        assertEquals(2, get(classes(org), teacher, org).body().get(0).path("memberCount").asInt());

        // removing a member
        assertEquals(204, call(HttpMethod.DELETE, members + "/" + other, null, teacher, org).status());
        assertEquals(404, call(HttpMethod.DELETE, members + "/" + other, null, teacher, org).status());
        assertEquals(403, call(HttpMethod.DELETE, members + "/" + learner, null, learner, org).status(), "a learner cannot remove even themselves");
        assertEquals(1, su.queryForObject("select count(*) from class_members where class_id = ?", Integer.class, cls));
    }

    @Test
    void anotherOrganisationsClassIsNotFound() {
        UUID a = org("CENTER");
        UUID b = org("CENTER");
        UUID adminA = orgAdmin(a);
        UUID adminB = orgAdmin(b);
        UUID teacherA = teacher(a);
        UUID cls = UUID.fromString(post(classes(a), Map.of("name", "A", "teacherId", teacherA), adminA, a).body().path("id").asText());
        // B's admin through A's path: A is not the organisation of their context
        assertEquals(404, get(classes(a) + "/" + cls, adminB, b).status());
        assertEquals(404, post(classes(a), Map.of("name", "x", "teacherId", teacherA), adminB, b).status());
        // B's admin through B's own path: the class is not theirs
        assertEquals(404, get(classes(b) + "/" + cls, adminB, b).status());
        assertEquals(404, get(classes(b) + "/" + cls + "/members", adminB, b).status());
        // outside any organisation context nothing is reachable
        assertEquals(403, get(classes(a), adminA, null).status());
    }

    @Test
    void coursesForTheWholeClassUseTheSeatPath() {
        UUID org = org("CENTER");
        UUID admin = orgAdmin(org);
        UUID teacher = teacher(org);
        UUID l1 = learner(org);
        UUID l2 = learner(org);
        UUID course = course();
        UUID cls = UUID.fromString(post(classes(org), Map.of("name", "A", "teacherId", teacher), admin, org).body().path("id").asText());
        post(classes(org) + "/" + cls + "/members", Map.of("userIds", List.of(l1, l2)), admin, org);
        String path = classes(org) + "/" + cls + "/courses";

        // not enough seats: nothing is given (all or none)
        UUID small = licence(org, admin, 1, course);
        Res noSeat = post(path, Map.of("courseIds", List.of(course), "licenseId", small), admin, org);
        assertEquals(409, noSeat.status(), noSeat.raw());
        assertEquals("urn:e9x:problem:no-seat-available", noSeat.problemType());
        assertEquals(0, su.queryForObject("select count(*) from seat_assignments where license_id = ?", Integer.class, small));

        // the teacher does not spend the organisation's seats
        UUID big = licence(org, admin, 5, course);
        assertEquals(403, post(path, Map.of("courseIds", List.of(course), "licenseId", big), teacher, org).status());

        Res ok = post(path, Map.of("courseIds", List.of(course), "licenseId", big), admin, org);
        assertEquals(200, ok.status(), ok.raw());
        assertEquals(2, ok.body().path("assigned").asInt());
        assertEquals(2, su.queryForObject("select count(*) from seat_assignments where license_id = ? and revoked_at is null", Integer.class, big));
        assertEquals(2, su.queryForObject("select count(*) from entitlements where course_id = ? and source_seat_id is not null", Integer.class, course));
        assertEquals(2, su.queryForObject("select count(*) from audit_log where action = 'seat.assign' and detail->>'licenseId' = ?", Integer.class, big.toString()), "the seat path audits each seat");

        Res again = post(path, Map.of("courseIds", List.of(course), "licenseId", big), admin, org);
        assertEquals(0, again.body().path("assigned").asInt());
        assertEquals(2, again.body().path("alreadyHolding").size());

        // a course outside the licence
        assertEquals(422, post(path, Map.of("courseIds", List.of(course()), "licenseId", big), admin, org).status());
    }
}
