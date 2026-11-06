package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.english9x.support.TestPostgres;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The application's own connection is a plain role: not the owner, not superuser, not BYPASSRLS, so RLS applies. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RoleSecurityIT {

    static final String DB = TestPostgres.createDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired
    JdbcTemplate app;

    @Test
    void appSessionIsTheLoginRoleWithoutAnyRlsBypass() {
        assertEquals("e9x_app_login", app.queryForObject("select current_user", String.class));
        assertEquals("e9x_app_login", app.queryForObject("select session_user", String.class));
        assertFalse(app.queryForObject("select rolsuper from pg_roles where rolname = current_user", Boolean.class));
        assertFalse(app.queryForObject("select rolbypassrls from pg_roles where rolname = current_user", Boolean.class));
        assertFalse(app.queryForObject("select rolcreaterole or rolcreatedb from pg_roles where rolname = current_user", Boolean.class));
        assertEquals(0, app.queryForObject("select count(*) from pg_tables where schemaname='public' and tableowner = current_user", Integer.class));
        assertEquals(0, app.queryForObject(
                "select count(*) from pg_class c where c.relnamespace = 'public'::regnamespace and c.relkind = 'r' and pg_has_role(current_user, c.relowner, 'MEMBER')",
                Integer.class), "not a member of any table owner either");
    }

    @Test
    void rlsActuallyHidesRowsFromTheAppSession() {
        JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));
        UUID org = UUID.randomUUID();
        su.update("insert into organizations(id,name,type) values (?,'X','CENTER')", org);
        su.update("insert into classes(owner_org_id,name) values (?,'secret')", org);
        assertEquals(1, su.queryForObject("select count(*) from classes", Integer.class), "owner side sees the row");
        assertEquals(0, app.queryForObject("select count(*) from classes", Integer.class), "app session without context does not");
        assertThrows(DataAccessException.class,
                () -> app.update("insert into classes(owner_org_id,name) values (?, 'x')", org),
                "and cannot write it either");
        assertThrows(DataAccessException.class, () -> app.execute("create table sneaky(id int)"));
        assertThrows(DataAccessException.class, () -> app.queryForList("select * from flyway_schema_history"));
    }
}
