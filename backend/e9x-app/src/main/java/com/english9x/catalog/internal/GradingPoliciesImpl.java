package com.english9x.catalog.internal;

import com.english9x.catalog.api.GradingPolicies;
import com.english9x.domain.grading.GradingMode;
import com.english9x.domain.grading.GradingPolicyResolver;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs in the caller's transaction, so Row-Level Security answers for the bound organisation: asking with another organisation's id
 * finds no row, and the exam's mode applies. The precedence itself is the domain's {@link GradingPolicyResolver}.
 */
@Service
@Transactional(readOnly = true)
class GradingPoliciesImpl implements GradingPolicies {

    private final JdbcTemplate jdbc;
    private final CatalogProperties props;
    private final TestLookup tests;

    GradingPoliciesImpl(JdbcTemplate jdbc, CatalogProperties props, TestLookup tests) {
        this.jdbc = jdbc;
        this.props = props;
        this.tests = tests;
    }

    @Override
    public GradingMode resolve(String examCode, Optional<UUID> orgId, Optional<UUID> testVersionId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from exams where code = ?)", Boolean.class, examCode))) {
            throw new IllegalArgumentException("unknown exam: " + examCode);
        }
        Optional<GradingMode> test = testVersionId.flatMap(tests::testOf).flatMap(id ->
                mode("scope = 'TEST' and test_id = ? and exam_code = ?", id, examCode));
        Optional<GradingMode> org = orgId.flatMap(id ->
                mode("scope = 'ORG' and owner_org_id = ? and exam_code = ?", id, examCode));
        GradingMode exam = mode("scope = 'EXAM' and exam_code = ?", examCode).orElse(props.defaultMode());
        return GradingPolicyResolver.resolve(test, org, exam);
    }

    private Optional<GradingMode> mode(String where, Object... args) {
        return jdbc.query("select mode from grading_policies where " + where, (rs, n) -> GradingMode.valueOf(rs.getString(1)), args)
                .stream().findFirst();
    }
}
