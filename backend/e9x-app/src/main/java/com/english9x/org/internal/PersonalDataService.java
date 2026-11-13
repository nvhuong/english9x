package com.english9x.org.internal;

import com.english9x.audit.api.AuditLog;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal-data erasure (spec §15, Nghị định 13/2023): a SYS_ADMIN runs the audited erasure of one person's personal data. The scrubbing itself is the narrow
 * SECURITY DEFINER {@code erase_personal_data}; this records the action in the audit log with IDS ONLY (who erased whom), never the erased name or address — those
 * are read at view time from users / invitations, which the definer has just scrubbed, so the audit screen then shows a tombstone instead.
 */
@Service
class PersonalDataService {

    private final OrgAccess access;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    PersonalDataService(OrgAccess access, JdbcTemplate jdbc, AuditLog audit) {
        this.access = access;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    void erase(UUID userId) {
        access.requireSysAdmin();
        Integer exists = jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, userId);
        if (exists == null || exists == 0) {
            throw OrgProblems.notFound();
        }
        jdbc.query("select erase_personal_data(?)", (rs, n) -> rs.getInt(1), userId);
        audit.record("personal-data.erase", "user", userId, Map.of());
    }
}
