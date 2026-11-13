package com.english9x.privacy;

import com.english9x.audit.api.AuditLog;
import com.english9x.content.media.api.StagedFiles;
import com.english9x.org.api.Authz;
import com.english9x.org.api.Role;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

/**
 * Personal-data erasure (spec §15, Nghị định 13/2023): a SYS_ADMIN runs the audited erasure of one person's personal data.
 *
 * <p>Order (so a failure cannot silently orphan either side): the Speaking recordings are voice — biometric data — kept as objects in the store, so the OBJECTS are
 * deleted first ({@link StagedFiles#delete} throws on a store failure, which fails this transaction and leaves the rows intact for a retry), and only then does the
 * narrow SECURITY DEFINER {@code erase_personal_data} delete the recording rows and scrub the rest (account fields, invitations, notifications, finished work, the
 * learner's essays, the teacher's feedback, the transfer memo). The audit record is IDS ONLY (who erased whom); the erased name/address is read at view time from
 * users / invitations, which the definer has just scrubbed, so the audit screen then shows a tombstone.
 */
@Service
class PersonalDataService {

    private final Authz authz;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final StagedFiles storage;

    PersonalDataService(Authz authz, JdbcTemplate jdbc, AuditLog audit, StagedFiles storage) {
        this.authz = authz;
        this.jdbc = jdbc;
        this.audit = audit;
        this.storage = storage;
    }

    @Transactional
    void erase(UUID userId) {
        // English9x SYS_ADMIN only: the grant is held with no org and Authz answers true only in the English9x context (e9x_is_sys_admin wants the staff flag too).
        authz.require(Role.SYS_ADMIN, Optional.empty());
        Integer exists = jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, userId);
        if (exists == null || exists == 0) {
            throw notFound();
        }
        // delete the voice objects first: a store failure throws and rolls this back (the recording rows are still there, so a retry is safe — nothing is orphaned)
        List<String> recordingKeys = jdbc.query("select personal_data_recording_keys(?)", (rs, n) -> rs.getString(1), userId);
        for (String key : recordingKeys) {
            storage.delete(key);
        }
        jdbc.query("select erase_personal_data(?)", (rs, n) -> rs.getInt(1), userId);
        audit.record("personal-data.erase", "user", userId, Map.of());
    }

    private static ErrorResponseException notFound() {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The resource does not exist.");
        p.setType(URI.create("urn:e9x:problem:not-found"));
        p.setTitle("Not found");
        return new ErrorResponseException(HttpStatus.NOT_FOUND, p, null);
    }
}
