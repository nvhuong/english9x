package com.english9x.org.internal;

import com.english9x.audit.api.AuditLog;
import com.english9x.domain.grading.GradingMode;
import com.english9x.org.internal.GradingSettingsDtos.Grader;
import com.english9x.org.internal.GradingSettingsDtos.GradingSettingsRequest;
import com.english9x.org.internal.GradingSettingsDtos.GradingSettingsView;
import com.english9x.org.internal.OrgDtos.OrgType;
import com.english9x.org.internal.OrgDtos.OrgView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The centre grading configuration (F50, spec §10.3 line 271): which SIDE grades the centre's learners (its own teachers or English9x) and, for its own
 * teachers, the MODE (AI / TEACHER / BOTH). The queue routing itself is the database's ({@code grading_queue_side} reads the side set here); this writes it.
 *
 * <p>Only a CENTER configures a grading side; an ENTERPRISE's learners are always graded by English9x and have no teachers (spec §4), so the application
 * refuses {@code grader = CENTER} or a teacher mode for an ENTERPRISE with a 400, before the database's own guard
 * ({@code grading_policies} ORG rows may only be AI for a non-CENTER, db-test 37) would.
 */
@Service
class GradingSettingsService {

    private final OrgAccess access;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    GradingSettingsService(OrgAccess access, JdbcTemplate jdbc, AuditLog audit) {
        this.access = access;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    GradingSettingsView get(UUID orgId) {
        access.manageable(orgId);
        return view(orgId);
    }

    @Transactional
    GradingSettingsView set(UUID orgId, GradingSettingsRequest req) {
        OrgView org = access.manageable(orgId);
        boolean teacherMode = req.mode() != null && req.mode() != GradingMode.AI;
        if (org.type() == OrgType.ENTERPRISE && (req.grader() == Grader.CENTER || teacherMode)) {
            throw OrgProblems.gradingNotAllowedForEnterprise();
        }
        UUID actor = access.actingUser();
        // Only a CENTER keeps a side row; an ENTERPRISE is English9x (no row) and the policy above never lets e9x_app write one for it.
        if (org.type() == OrgType.CENTER) {
            jdbc.update("insert into org_grading_sides(org_id, side) values (?, ?) "
                    + "on conflict (org_id) do update set side = excluded.side, updated_at = now()", orgId, req.grader().name());
        }
        if (req.mode() != null) {
            // One mode for the centre's learners across the exams of v1 (grading_policies is keyed per exam); the ORG row wins over the exam default (§10.3).
            for (String exam : jdbc.queryForList("select code from exams order by code", String.class)) {
                jdbc.update("insert into grading_policies(owner_org_id, exam_code, scope, mode, updated_by) values (?, ?, 'ORG', ?, ?) "
                        + "on conflict (owner_org_id, exam_code) where scope = 'ORG' do update set mode = excluded.mode, updated_by = excluded.updated_by, updated_at = now()",
                        orgId, exam, req.mode().name(), actor);
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("grader", req.grader().name());
        detail.put("mode", req.mode() == null ? "unchanged" : req.mode().name());
        audit.record("grading.settings", "organization", orgId, detail);
        return view(orgId);
    }

    private GradingSettingsView view(UUID orgId) {
        Grader grader = jdbc.query("select side from org_grading_sides where org_id = ?",
                (rs, n) -> Grader.valueOf(rs.getString(1)), orgId).stream().findFirst().orElse(Grader.ENGLISH9X);
        List<GradingMode> modes = jdbc.query("select mode from grading_policies where owner_org_id = ? and scope = 'ORG' order by exam_code",
                (rs, n) -> GradingMode.valueOf(rs.getString(1)), orgId);
        // The modes are written together, so the first stands for the organisation; none means the exam default applies.
        GradingMode mode = modes.stream().findFirst().orElse(null);
        return new GradingSettingsView(grader, mode);
    }
}
