package com.english9x.identity.internal;

import com.english9x.identity.api.EmailNotVerifiedException;
import com.english9x.identity.api.UserDirectory;
import com.english9x.identity.api.UserView;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads {@code users} through Row-Level Security as whoever is bound to the thread (see {@link UserDirectory}). */
@Service
class UserDirectoryImpl implements UserDirectory {

    private final JdbcTemplate jdbc;

    UserDirectoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserView> findById(UUID userId) {
        return jdbc.query("select id, email, display_name, email_verified_at is not null as verified, locale from users where id = ?",
                (rs, n) -> new UserView(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                        rs.getBoolean("verified"), rs.getString("locale")),
                userId).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true) // own transaction: a self-call to findById would bypass the proxy and run without the RLS context
    public void requireVerified(UUID userId) {
        if (findById(userId).map(UserView::emailVerified).orElse(false) == false) {
            throw new EmailNotVerifiedException();
        }
    }
}
