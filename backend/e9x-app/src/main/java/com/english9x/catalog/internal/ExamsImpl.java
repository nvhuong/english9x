package com.english9x.catalog.internal;

import com.english9x.catalog.api.ExamInfo;
import com.english9x.catalog.api.ExamSection;
import com.english9x.catalog.api.Exams;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Exams and their sections, read from the reference tables (no tenant data, no Row-Level Security). */
@Service
class ExamsImpl implements Exams {

    private final JdbcTemplate jdbc;

    ExamsImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ExamInfo> all() {
        Map<String, List<ExamSection>> sections = new LinkedHashMap<>();
        jdbc.query("select exam_code, code, skill, position, item_count from exam_sections order by exam_code, position", rs -> {
            sections.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                    .add(new ExamSection(rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)));
        });
        return jdbc.query("select code, name, variants, score_skills from exams order by code",
                (rs, n) -> new ExamInfo(rs.getString(1), rs.getString(2), strings(rs.getArray(3)), strings(rs.getArray(4)),
                        List.copyOf(sections.getOrDefault(rs.getString(1), List.of()))));
    }

    @Override
    public Optional<ExamInfo> find(String code) {
        return all().stream().filter(e -> e.code().equals(code)).findFirst();
    }

    @Override
    public boolean exists(String code) {
        return code != null && Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from exams where code = ?)", Boolean.class, code));
    }

    private static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : List.copyOf(Arrays.asList((String[]) a.getArray()));
    }
}
