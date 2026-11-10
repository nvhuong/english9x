package com.english9x.content.importer;

import com.english9x.catalog.api.FieldError;
import com.english9x.catalog.api.SchemaValidator;
import com.english9x.content.importer.Column.Kind;
import com.english9x.content.importer.Column.Side;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One row of cells to one question: the cells go to the places their columns name, the two documents are checked against the schemas of the type (the
 * same {@link SchemaValidator} as the question form), and every problem is reported with its row and its column. Pure: no database, no files. A file named
 * in a media column is put in the document as a stand-in id so that the schema (which wants a UUID there) can run; {@link Parsed#media} says which file belongs where, and
 * the importer swaps the real id in once the file is stored.
 */
final class RowParser {

    /** What stands for a media file's id while the schemas run: the schema only wants the shape of an id; whether the file exists is checked by name. */
    static final String PLACEHOLDER_ID = "00000000-0000-4000-8000-000000000000";

    /** A file a row points at: the cell holds {@code filename}; the id of the stored file goes to {@code path} of {@code side}. */
    record MediaRef(Side side, List<Object> path, String column, String filename, String wantedKind) {}

    record Parsed(int row, ObjectNode content, ObjectNode answerKey, String explanation, BigDecimal points, List<MediaRef> media) {}

    /** {@code parsed} is {@code null} exactly when {@code errors} is not empty. */
    record Result(Parsed parsed, List<RowError> errors) {}

    private static final Pattern TOKEN = Pattern.compile("\\[(\\d+)]|\\.?([^.\\[\\]]+)");

    private final ColumnPlan plan;
    private final SchemaValidator schemas;
    private final Map<String, Column> byHeader;

    RowParser(ColumnPlan plan, SchemaValidator schemas) {
        this.plan = plan;
        this.schemas = schemas;
        this.byHeader = plan.byHeader();
    }

    /** @param cells the text of each cell by lower-case header; blank cells may be absent or empty */
    Result parse(int row, Map<String, String> cells) {
        List<RowError> errors = new ArrayList<>();
        Map<String, Object> content = new LinkedHashMap<>();
        Map<String, Object> key = new LinkedHashMap<>();
        List<MediaRef> media = new ArrayList<>();
        for (Column c : plan.columns()) {
            String text = cells.get(c.header().toLowerCase(Locale.ROOT));
            if (text == null || text.isBlank()) {
                continue;
            }
            JsonNode value = convert(row, c, text.strip(), errors);
            if (value == null) {
                continue;
            }
            if (c.kind() == Kind.MEDIA) {
                media.add(new MediaRef(c.side(), c.path(), c.header(), text.strip(), c.mediaKind()));
                value = JsonNodeFactory.instance.textNode(PLACEHOLDER_ID);
            }
            place(c.side() == Side.ANSWER ? key : content, c.path(), value);
        }
        BigDecimal points = points(row, cells.get(ColumnPlan.POINTS), errors);
        String explanation = cells.get(ColumnPlan.EXPLANATION);
        ObjectNode contentNode = (ObjectNode) toJson(content, Side.CONTENT, List.of(), row, errors);
        ObjectNode keyNode = (ObjectNode) toJson(key, Side.ANSWER, List.of(), row, errors);
        if (errors.isEmpty()) {
            for (FieldError e : schemas.validate(plan.typeCode(), contentNode, keyNode)) {
                errors.add(map(row, e));
            }
        }
        if (!errors.isEmpty()) {
            return new Result(null, List.copyOf(errors));
        }
        return new Result(new Parsed(row, contentNode, keyNode, explanation == null || explanation.isBlank() ? null : explanation.strip(), points,
                List.copyOf(media)), List.of());
    }

    /** Puts the id of a stored file where the stand-in is. */
    static void substitute(Parsed row, MediaRef ref, java.util.UUID mediaId) {
        JsonNode at = ref.side() == Side.ANSWER ? row.answerKey() : row.content();
        for (int i = 0; i < ref.path().size(); i++) {
            Object seg = ref.path().get(i);
            boolean last = i == ref.path().size() - 1;
            if (last) {
                ((ObjectNode) at).put((String) seg, mediaId.toString());
            } else {
                at = seg instanceof Integer n ? at.get(n) : at.get((String) seg);
            }
        }
    }

    // ---- cells to values ----------------------------------------------------------------------------------------------------------

    private JsonNode convert(int row, Column c, String text, List<RowError> errors) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (c.list()) {
            ArrayNode a = f.arrayNode();
            for (String part : text.split("\\|")) {
                if (!part.isBlank()) {
                    a.add(part.strip());
                }
            }
            if (a.isEmpty()) {
                return null;
            }
            return a;
        }
        try {
            return switch (c.kind()) {
                case STRING, MEDIA -> f.textNode(text);
                case INTEGER -> f.numberNode(new BigDecimal(text.replace(',', '.')).intValueExact());
                case NUMBER -> f.numberNode(new BigDecimal(text.replace(',', '.')));
                case BOOLEAN -> f.booleanNode(bool(text));
            };
        } catch (ArithmeticException | NumberFormatException e) {
            errors.add(new RowError(row, c.header(), c.kind() == Kind.INTEGER ? "phải là số nguyên" : "phải là số"));
        } catch (IllegalArgumentException e) {
            errors.add(new RowError(row, c.header(), "phải là TRUE hoặc FALSE"));
        }
        return null;
    }

    private static boolean bool(String t) {
        return switch (t.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "có", "co" -> true;
            case "false", "0", "no", "không", "khong" -> false;
            default -> throw new IllegalArgumentException(t);
        };
    }

    private static BigDecimal points(int row, String text, List<RowError> errors) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            BigDecimal p = new BigDecimal(text.strip().replace(',', '.'));
            if (p.signum() <= 0 || p.compareTo(BigDecimal.valueOf(1000)) > 0 || p.scale() > 2) {
                throw new NumberFormatException();
            }
            return p;
        } catch (NumberFormatException e) {
            errors.add(new RowError(row, ColumnPlan.POINTS, "phải lớn hơn 0, tối đa 1000, tối đa hai chữ số thập phân"));
            return null;
        }
    }

    // ---- the tree -----------------------------------------------------------------------------------------------------------------

    /** Objects are maps, arrays are maps by position (a gap is then visible); {@link #toJson} makes the document. */
    @SuppressWarnings("unchecked")
    private static void place(Map<String, Object> root, List<Object> path, JsonNode leaf) {
        Object cur = root;
        for (int i = 0; i < path.size(); i++) {
            Object seg = path.get(i);
            boolean last = i == path.size() - 1;
            Object child = last ? leaf : (path.get(i + 1) instanceof Integer ? new TreeMap<Integer, Object>() : new LinkedHashMap<String, Object>());
            if (seg instanceof String s) {
                cur = last ? ((Map<String, Object>) cur).put(s, leaf) : ((Map<String, Object>) cur).computeIfAbsent(s, k -> child);
            } else {
                cur = last ? ((Map<Integer, Object>) cur).put((Integer) seg, leaf) : ((Map<Integer, Object>) cur).computeIfAbsent((Integer) seg, k -> child);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private JsonNode toJson(Object node, Side side, List<Object> at, int row, List<RowError> errors) {
        if (node instanceof JsonNode j) {
            return j;
        }
        if (node instanceof TreeMap<?, ?> arr) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            int expected = 0;
            for (Map.Entry<Integer, Object> e : ((TreeMap<Integer, Object>) arr).entrySet()) {
                for (; expected < e.getKey(); expected++) {
                    Column col = plan.nearest(side, append(at, expected));
                    errors.add(new RowError(row, col == null ? null : col.header(), "thiếu " + (side == Side.ANSWER ? "đáp án" : "giá trị") + ": ô trống nằm giữa các ô có dữ liệu"));
                }
                expected = e.getKey() + 1;
                out.add(toJson(e.getValue(), side, append(at, e.getKey()), row, errors));
            }
            return out;
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ((Map<String, Object>) node).forEach((k, v) -> out.set(k, toJson(v, side, append(at, k), row, errors)));
        return out;
    }

    private static List<Object> append(List<Object> path, Object seg) {
        List<Object> next = new ArrayList<>(path);
        next.add(seg);
        return next;
    }

    // ---- schema errors back to columns -----------------------------------------------------------------------------------------------

    private RowError map(int row, FieldError e) {
        Side side = e.path().startsWith("answerKey") ? Side.ANSWER : Side.CONTENT;
        String rest = e.path().substring(side == Side.ANSWER ? "answerKey".length() : "content".length());
        List<Object> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(rest);
        while (m.find()) {
            tokens.add(m.group(1) != null ? (Object) Integer.valueOf(m.group(1)) : m.group(2));
        }
        Column column = plan.nearest(side, tokens);
        if (column == null) {
            column = plan.first(side);
        }
        String header = column == null ? null : column.header();
        String message = e.message();
        if (message.equals("is required")) {
            message = side == Side.ANSWER ? "thiếu đáp án" : "thiếu " + (header == null ? "giá trị" : header);
        } else if (side == Side.ANSWER) {
            message = switch (message) {
                case "has the wrong type" -> "đáp án sai kiểu dữ liệu";
                case "is not an allowed value" -> "đáp án không hợp lệ";
                case "has the wrong format" -> "đáp án sai định dạng";
                case "is too small or too short" -> "đáp án quá nhỏ hoặc quá ngắn";
                case "is too large or too long" -> "đáp án quá lớn hoặc quá dài";
                case "has repeated entries" -> "đáp án có giá trị lặp";
                default -> "đáp án " + message;
            };
        }
        return new RowError(row, header, message);
    }
}
