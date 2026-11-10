package com.english9x.content.scoring.internal;

import com.english9x.content.scoring.api.ScoreRow;
import com.english9x.content.scoring.internal.ScoreProblems.FieldProblem;
import com.english9x.domain.scoring.TableConverter;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The rows of a conversion table: each {@code {raw, scaled}} is read strictly (a whole raw from 0, a finite scaled score; a string or a fractional raw is
 * refused, never coerced), and the whole table must be accepted by the domain's {@link TableConverter#of} (raw contiguous from 0, scaled never
 * decreasing), the very check that scores a result. The database keeps at most 1000 rows.
 */
final class RowsValidator {

    static final int MAX_ROWS = 1000;

    record Result(List<ScoreRow> rows, List<FieldProblem> errors) {}

    private RowsValidator() {}

    static Result validate(JsonNode rows) {
        List<FieldProblem> errors = new ArrayList<>();
        if (rows == null || !rows.isArray()) {
            errors.add(new FieldProblem("rows", "must be a list of {raw, scaled}"));
            return new Result(List.of(), errors);
        }
        if (rows.isEmpty()) {
            errors.add(new FieldProblem("rows", "needs at least one row"));
            return new Result(List.of(), errors);
        }
        if (rows.size() > MAX_ROWS) {
            errors.add(new FieldProblem("rows", "has more than " + MAX_ROWS + " rows"));
            return new Result(List.of(), errors);
        }
        Map<Integer, Double> table = new TreeMap<>();
        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            JsonNode raw = row.path("raw");
            JsonNode scaled = row.path("scaled");
            boolean ok = true;
            if (!row.isObject()) {
                errors.add(new FieldProblem("rows[" + i + "]", "must be {raw, scaled}"));
                continue;
            }
            if (!raw.isIntegralNumber() || !raw.canConvertToInt() || raw.intValue() < 0) {
                errors.add(new FieldProblem("rows[" + i + "].raw", "must be a whole number, 0 or more"));
                ok = false;
            }
            if (!scaled.isNumber() || !Double.isFinite(scaled.doubleValue()) || scaled.doubleValue() < 0) {
                errors.add(new FieldProblem("rows[" + i + "].scaled", "must be a number, 0 or more"));
                ok = false;
            }
            if (ok && table.put(raw.intValue(), scaled.doubleValue()) != null) {
                errors.add(new FieldProblem("rows[" + i + "].raw", "repeats raw " + raw.intValue()));
            }
        }
        if (errors.isEmpty()) {
            try {
                TableConverter.of(table);
            } catch (IllegalArgumentException e) {
                errors.add(new FieldProblem("rows", e.getMessage()));
            }
        }
        if (!errors.isEmpty()) {
            return new Result(List.of(), errors);
        }
        List<ScoreRow> sorted = new ArrayList<>();
        table.forEach((raw, scaled) -> sorted.add(new ScoreRow(raw, scaled)));
        return new Result(sorted, errors);
    }
}
