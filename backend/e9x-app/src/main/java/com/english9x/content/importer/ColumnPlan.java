package com.english9x.content.importer;

import com.english9x.content.importer.Column.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** The columns of one question type: the reserved ones, then those of the content schema, then those of the answer-key schema. */
record ColumnPlan(String typeCode, List<Column> columns) {

    /** Reserved, not part of the schema: a free note (ignored; the example row is marked with it), the points of the question, its explanation. */
    static final String NOTE = "note";
    static final String POINTS = "points";
    static final String EXPLANATION = "explanation";
    static final List<String> RESERVED = List.of(NOTE, POINTS, EXPLANATION);
    /** The value of {@link #NOTE} that marks the example row: the importer skips it. */
    static final String EXAMPLE_MARK = "VÍ DỤ";

    ColumnPlan {
        columns = List.copyOf(columns);
    }

    /** Every header of the template, in order. */
    List<String> headers() {
        List<String> out = new ArrayList<>(RESERVED);
        columns.forEach(c -> out.add(c.header()));
        return out;
    }

    Map<String, Column> byHeader() {
        return columns.stream().collect(Collectors.toMap(c -> c.header().toLowerCase(Locale.ROOT), c -> c, (a, b) -> a, java.util.LinkedHashMap::new));
    }

    /** The column that holds {@code path} of that side, or else the one that shares the longest beginning with it (a missing array, a missing object). */
    Column nearest(Side side, List<Object> path) {
        Column best = null;
        int bestShared = -1;
        for (Column c : columns) {
            if (c.side() != side) {
                continue;
            }
            int shared = 0;
            while (shared < c.path().size() && shared < path.size() && c.path().get(shared).equals(path.get(shared))) {
                shared++;
            }
            if (shared > bestShared) {
                best = c;
                bestShared = shared;
            }
        }
        return best;
    }

    Column first(Side side) {
        return columns.stream().filter(c -> c.side() == side).findFirst().orElse(null);
    }
}
