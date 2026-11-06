package com.english9x.platform.jobs;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fails startup when the worker connection is not the narrow worker login: a superuser or BYPASSRLS role, a role with
 * CREATEROLE / CREATEDB / REPLICATION, a table owner, a role that is not a member of {@code e9x_worker}, or one that is
 * also an application or grader role (which would hand the worker, or the request path, the other's privileges).
 */
final class WorkerRoleGuard {

    private static final String CHECK = """
            select r.rolname,
                   r.rolsuper or r.rolbypassrls as bypasses_rls,
                   r.rolcreaterole or r.rolcreatedb or r.rolreplication as privileged,
                   exists (select 1 from pg_class c
                            where c.relnamespace = 'public'::regnamespace and c.relkind in ('r','p')
                              and pg_has_role(current_user, c.relowner, 'MEMBER')) as owns_a_table,
                   exists (select 1 from pg_roles g where g.rolname = 'e9x_worker'
                            and pg_has_role(current_user, g.oid, 'MEMBER')) as is_worker,
                   exists (select 1 from pg_roles g where g.rolname in ('e9x_app', 'e9x_grader')
                            and pg_has_role(current_user, g.oid, 'MEMBER')) as is_app_or_grader
              from pg_roles r where r.rolname = current_user
            """;

    private WorkerRoleGuard() {}

    static void verify(DataSource dataSource) {
        new JdbcTemplate(dataSource).query(CHECK, rs -> {
            String role = rs.getString("rolname");
            if (rs.getBoolean("bypasses_rls")) {
                throw new IllegalStateException("worker database role " + role + " is a superuser or has BYPASSRLS: refusing to start");
            }
            if (rs.getBoolean("privileged")) {
                throw new IllegalStateException("worker database role " + role + " has CREATEROLE, CREATEDB or REPLICATION: refusing to start");
            }
            if (rs.getBoolean("owns_a_table")) {
                throw new IllegalStateException("worker database role " + role + " owns tables, so RLS would not apply to it: connect as e9x_worker_login");
            }
            if (!rs.getBoolean("is_worker")) {
                throw new IllegalStateException("database role " + role + " is not a member of e9x_worker: the worker must connect as e9x_worker_login");
            }
            if (rs.getBoolean("is_app_or_grader")) {
                throw new IllegalStateException("worker database role " + role + " is also a member of e9x_app or e9x_grader: the worker must not hold request-path privileges");
            }
        });
    }
}
