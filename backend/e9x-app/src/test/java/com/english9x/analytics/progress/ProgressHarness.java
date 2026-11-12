package com.english9x.analytics.progress;

import com.english9x.analytics.results.ResultsHarness;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Rows for the progress tests: plans and orders, seats, extra lessons, completed lessons, assignments. */
public abstract class ProgressHarness extends ResultsHarness {

    protected record Lessons(UUID course, List<UUID> lessonIds, List<UUID> refIds) {}

    /** A published course (version 1) of {@code n} lessons in one chapter. */
    protected Lessons course(String title, int n) {
        UUID course = methodLesson("TOEIC_P5_INCOMPLETE_SENTENCE", title + " 1", null, Duration.ZERO, Duration.ZERO);
        UUID version = su.queryForObject("select id from course_versions where course_id = ?", UUID.class, course);
        UUID author = su.queryForObject("select created_by from course_versions where id = ?", UUID.class, version);
        su.execute("alter table course_lessons disable trigger user");
        for (int i = 2; i <= n; i++) {
            UUID lesson = UUID.randomUUID();
            UUID lessonVersion = UUID.randomUUID();
            su.update("insert into strategy_lessons(id,owner_org_id,exam_code,created_by) values (?,null,'TOEIC',?)", lesson, author);
            su.update("insert into strategy_lesson_versions(id,lesson_id,owner_org_id,exam_code,version_no,status,title,blocks,created_by) values (?,?,null,'TOEIC',1,'PUBLISHED',?,'[]'::jsonb,?)",
                    lessonVersion, lesson, title + " " + i, author);
            su.update("insert into course_lessons(course_version_id,chapter_position,position,kind,ref_id,title,owner_org_id,exam_code) values (?,1,?,'LESSON',?,?,null,'TOEIC')", version, i,
                    lessonVersion, title + " " + i);
        }
        su.execute("alter table course_lessons enable trigger user");
        List<UUID> ids = su.queryForList("select id from course_lessons where course_version_id = ? order by position", UUID.class, version);
        List<UUID> refs = su.queryForList("select ref_id from course_lessons where course_version_id = ? order by position", UUID.class, version);
        return new Lessons(course, ids, refs);
    }

    protected UUID plan(String name, int maxCourses) {
        UUID id = UUID.randomUUID();
        su.update("insert into plans(id,name,price_vnd,months,max_courses) values (?,?,100000,3,?)", id, name, maxCourses);
        return id;
    }

    protected UUID order(UUID user, UUID plan) {
        UUID id = UUID.randomUUID();
        su.update("insert into orders(id,code,user_id,plan_id,plan_months,amount_vnd,status,confirmed_at) values (?,?,?,?,3,100000,'CONFIRMED',now())", id,
                UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(), user, plan);
        return id;
    }

    protected void orderEntitlement(UUID user, UUID course, UUID order, Duration startedAgo, Duration length) {
        Instant from = clock.instant().minus(startedAgo);
        su.update("insert into entitlements(user_id,course_id,valid_from,valid_until,source_order_id) values (?,?,?,?,?)", user, course, Timestamp.from(from), Timestamp.from(from.plus(length)), order);
    }

    protected void seatEntitlement(UUID org, UUID admin, UUID user, UUID course, Duration startedAgo, Duration length) {
        Instant from = clock.instant().minus(startedAgo);
        UUID license = UUID.randomUUID();
        su.update("insert into org_licenses(id,org_id,seats,months,price_vnd,activation,contract_no,status,valid_from,valid_until,created_by) values (?,?,5,3,1000,'CONTRACT','C-1','ACTIVE',?,?,?)", license,
                org, Timestamp.from(from), Timestamp.from(from.plus(length)), admin);
        su.update("insert into org_license_courses(license_id,org_id,course_id) values (?,?,?)", license, org, course);
        UUID seat = UUID.randomUUID();
        su.update("insert into seat_assignments(id,license_id,org_id,user_id,assigned_by) values (?,?,?,?,?)", seat, license, org, user, admin);
        su.update("insert into entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id) values (?,?,?,?,?,?)", user, course, Timestamp.from(from),
                Timestamp.from(from.plus(length)), seat, org);
    }

    protected void complete(UUID user, UUID course, UUID refId) {
        su.update("insert into lesson_progress(user_id,course_id,ref_id,kind,completed,completed_at) values (?,?,?,'LESSON',true,?) on conflict do nothing", user, course, refId,
                Timestamp.from(clock.instant()));
    }

    protected void assign(UUID org, UUID admin, UUID learner, UUID lessonId, Duration dueIn) {
        UUID klass = UUID.randomUUID();
        su.update("insert into classes(id,owner_org_id,name) values (?,?,?)", klass, org, "Class " + klass.toString().substring(0, 4));
        su.update("insert into class_members(class_id,org_id,user_id) values (?,?,?)", klass, org, learner);
        su.update("insert into assignments(class_id,org_id,lesson_id,due_at,created_by) values (?,?,?,?,?)", klass, org, lessonId, Timestamp.from(clock.instant().plus(dueIn)), admin);
    }
}
