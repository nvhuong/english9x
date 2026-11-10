package com.english9x.content.test;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Rows of {@code tests} and {@code test_versions} written as the superuser, for the tests of other modules that need a real test (the overrides are keyed by it). */
public final class TestRows {

    private TestRows() {}

    /** A person to be the author of fixtures (tests and their versions record one). */
    public static UUID author(JdbcTemplate su) {
        UUID id = UUID.randomUUID();
        su.update("insert into users(id,email) values (?,?)", id, id + "@fixture.test");
        return id;
    }

    /** A test (header) of the owner (null = English9x). */
    public static UUID test(JdbcTemplate su, UUID owner, String exam, String variant, UUID by) {
        UUID id = UUID.randomUUID();
        su.update("insert into tests(id,owner_org_id,exam_code,variant,created_by) values (?,?,?,?,?)", id, owner, exam, variant, by);
        return id;
    }

    /** A version of the test; status as given (the superuser may insert any status). */
    public static UUID version(JdbcTemplate su, UUID testId, UUID owner, String exam, String variant, int no, String status, UUID by) {
        UUID id = UUID.randomUUID();
        su.update("insert into test_versions(id,test_id,owner_org_id,exam_code,variant,version_no,status,title,created_by) values (?,?,?,?,?,?,?,'Fixture test',?)",
                id, testId, owner, exam, variant, no, status, by);
        return id;
    }
}
