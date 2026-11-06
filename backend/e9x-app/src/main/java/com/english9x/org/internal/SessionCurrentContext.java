package com.english9x.org.internal;

import com.english9x.org.api.CurrentContext;
import com.english9x.org.api.CurrentContextHolder;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link CurrentContext} of the running thread. The user and org come from the {@link CurrentContextHolder} binding;
 * the privilege flags are read back from the database session settings that {@link RlsSessionConfigurer} derived from
 * role data when the current transaction began, so there is exactly one source of truth. Outside a transaction the
 * settings are empty and both flags are false.
 */
@Component
class SessionCurrentContext implements CurrentContext {

    private final JdbcTemplate jdbc;

    SessionCurrentContext(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID userId() {
        return CurrentContextHolder.current()
                .orElseThrow(() -> new IllegalStateException("no user context is bound to this thread"))
                .userId();
    }

    @Override
    public Optional<UUID> orgId() {
        return CurrentContextHolder.current().flatMap(CurrentContextHolder.Binding::orgId);
    }

    @Override
    public boolean isEnglish9xStaff() {
        return flag("app.e9x_staff");
    }

    @Override
    public boolean isOrgAdmin() {
        return flag("app.org_admin");
    }

    private boolean flag(String name) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("select coalesce(current_setting(?, true), '') = 'on'", Boolean.class, name));
    }
}
