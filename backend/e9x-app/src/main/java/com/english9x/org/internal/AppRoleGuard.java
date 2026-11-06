package com.english9x.org.internal;

import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fails startup when the application's database login could bypass Row-Level Security: a superuser, a BYPASSRLS
 * role, or a role that owns (or inherits ownership of) a table in schema public. Flyway must run as the owner
 * (e9x_owner) and the application as e9x_app_login; if they were mixed up every RLS policy would silently not apply.
 */
@Component
class AppRoleGuard implements SmartInitializingSingleton {

    private static final String CHECK = """
            select r.rolname,
                   r.rolsuper or r.rolbypassrls as bypasses_rls,
                   exists (select 1 from pg_class c
                            where c.relnamespace = 'public'::regnamespace and c.relkind in ('r','p')
                              and pg_has_role(current_user, c.relowner, 'MEMBER')) as owns_a_table
              from pg_roles r where r.rolname = current_user
            """;

    private final DataSource dataSource;

    AppRoleGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verify(dataSource);
    }

    static void verify(DataSource dataSource) {
        new JdbcTemplate(dataSource).query(CHECK, rs -> {
            String role = rs.getString("rolname");
            if (rs.getBoolean("bypasses_rls")) {
                throw new IllegalStateException("database role " + role + " is a superuser or has BYPASSRLS: refusing to start without Row-Level Security");
            }
            if (rs.getBoolean("owns_a_table")) {
                throw new IllegalStateException("database role " + role + " owns tables, so RLS would not apply to it: connect as e9x_app_login and migrate as e9x_owner");
            }
        });
    }
}
