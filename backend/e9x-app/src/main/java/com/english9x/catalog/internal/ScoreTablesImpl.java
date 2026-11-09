package com.english9x.catalog.internal;

import com.english9x.catalog.api.ResolvedScoreTable;
import com.english9x.catalog.api.ScoreFunction;
import com.english9x.catalog.api.ScoreTableNotFoundException;
import com.english9x.catalog.api.ScoreTables;
import com.english9x.domain.scoring.ScoreConverter;
import com.english9x.domain.scoring.TableConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statements run in the caller's transaction, so Row-Level Security answers for the bound user and organisation: a test's table
 * of another organisation is simply not there. Only PUBLISHED versions count; of several, the highest number wins.
 */
@Service
@Transactional(readOnly = true)
class ScoreTablesImpl implements ScoreTables {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Found(UUID versionId, ScoreFunction function, String entries) {}

    private final JdbcTemplate jdbc;
    private final TestLookup tests;

    ScoreTablesImpl(JdbcTemplate jdbc, TestLookup tests) {
        this.jdbc = jdbc;
        this.tests = tests;
    }

    @Override
    public ResolvedScoreTable resolve(String examCode, String skill, Optional<UUID> testVersionId) {
        List<String> skills = jdbc.query("select score_skills from exams where code = ?",
                (rs, n) -> List.of((String[]) rs.getArray(1).getArray()), examCode).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown exam: " + examCode));
        if (!skills.contains(skill)) {
            throw new IllegalArgumentException("exam " + examCode + " keeps no score under " + skill + " (it keeps " + skills + ")");
        }
        // the override belongs to the TEST of the version (TestLookup; none exists before P2.6, and no TEST row can either)
        Optional<UUID> testId = testVersionId.flatMap(tests::testOf);
        if (testId.isPresent()) {
            Optional<Found> override = latestPublished("t.scope = 'TEST' and t.test_id = ?", examCode, skill, testId.get());
            if (override.isPresent()) {
                return result(examCode, skill, override.get(), true);
            }
        }
        Found found = latestPublished("t.scope = 'EXAM'", examCode, skill)
                .orElseThrow(() -> new ScoreTableNotFoundException(examCode, skill));
        return result(examCode, skill, found, false);
    }

    private Optional<Found> latestPublished(String scope, String examCode, String skill, Object... scopeArgs) {
        Object[] args = new Object[2 + scopeArgs.length];
        args[0] = examCode;
        args[1] = skill;
        System.arraycopy(scopeArgs, 0, args, 2, scopeArgs.length);
        return jdbc.query("""
                select v.id, t.function, v.entries::text
                  from score_tables t join score_table_versions v on v.score_table_id = t.id
                 where t.exam_code = ? and t.skill = ? and %s and v.status = 'PUBLISHED'
                 order by v.version_no desc, v.id
                 limit 1
                """.formatted(scope),
                (rs, n) -> new Found(rs.getObject(1, UUID.class), ScoreFunction.valueOf(rs.getString(2)), rs.getString(3)), args)
                .stream().findFirst();
    }

    private ResolvedScoreTable result(String examCode, String skill, Found found, boolean override) {
        Optional<ScoreConverter> converter = found.function() == ScoreFunction.TABLE
                ? Optional.of(converter(found)) : Optional.empty();
        return new ResolvedScoreTable(found.versionId(), examCode, skill, found.function(), converter, override);
    }

    /** A published table that the domain refuses is a data error that must stop the scoring, not be papered over. */
    private static ScoreConverter converter(Found found) {
        try {
            Map<Integer, Double> table = new TreeMap<>();
            for (JsonNode e : JSON.readTree(found.entries())) {
                if (!e.path("raw").isInt() || !e.path("scaled").isNumber() || table.put(e.get("raw").asInt(), e.get("scaled").asDouble()) != null) {
                    throw new IllegalArgumentException("each entry needs an integer raw, a number scaled, and raw must not repeat");
                }
            }
            return TableConverter.of(table);
        } catch (IllegalArgumentException | java.io.IOException e) {
            throw new IllegalStateException("published score table version " + found.versionId() + " is not a valid table: " + e.getMessage(), e);
        }
    }
}
