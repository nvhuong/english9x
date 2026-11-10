package com.english9x.content.importer;

import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.content.importer.Column.Kind;
import com.english9x.content.importer.Column.Side;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * The {@code .xlsx} template of a question type, made from the columns of its JSON Schema ({@link ColumnPlanner}): sheet {@code Huong_dan} (what each
 * column takes, whether it is required, its limits) and sheet {@code Du_lieu} (the header row, one example row, drop-down lists for the columns that
 * take a fixed set of values). The example row carries {@link ColumnPlan#EXAMPLE_MARK} in its {@code note} cell: the importer skips it.
 */
@Component
public class TemplateGenerator {

    static final String GUIDE_SHEET = "Huong_dan";
    static final String DATA_SHEET = "Du_lieu";

    private final ImportProperties props;

    TemplateGenerator(ImportProperties props) {
        this.props = props;
    }

    /** The columns the template of this type has: the reserved ones, then those of the schemas. */
    public static List<String> headersOf(QuestionTypeDef def) {
        return ColumnPlanner.plan(def).headers();
    }

    public byte[] generate(QuestionTypeDef def) {
        ColumnPlan plan = ColumnPlanner.plan(def);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            guide(wb, def, plan);
            data(wb, plan);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("cannot write the template", e);
        }
    }

    private void guide(XSSFWorkbook wb, QuestionTypeDef def, ColumnPlan plan) {
        Sheet s = wb.createSheet(GUIDE_SHEET);
        CellStyle bold = bold(wb);
        CellStyle wrap = wb.createCellStyle();
        wrap.setWrapText(true);
        int r = 0;
        s.createRow(r++).createCell(0).setCellValue("Mẫu import câu hỏi: " + def.code());
        s.getRow(0).getCell(0).setCellStyle(bold);
        String[] lines = {
            "1. Điền dữ liệu vào sheet " + DATA_SHEET + ", mỗi dòng là một câu hỏi. Dòng 1 là tiêu đề cột, đừng sửa. Xoá dòng ví dụ (dòng 2) hoặc ghi đè lên nó.",
            "2. Cột có dấu * là bắt buộc. Cột có danh sách chọn chỉ nhận các giá trị trong danh sách.",
            "3. Nhóm cột đánh số (options.1.key, options.1.text, options.2.key, ...) là các phần tử của một danh sách: điền từ số 1, không bỏ ô trống ở giữa.",
            "4. Mỗi ô đáp án (answer.slots.N) là một chỗ trống; nhiều đáp án chấp nhận được cho cùng một chỗ thì cách nhau bởi dấu |.",
            "5. Cột *_media_file ghi đúng tên tệp (có đuôi) nằm trong file zip media tải lên cùng; tên tệp phân biệt chữ hoa, chữ thường.",
            "6. Tối đa " + props.maxRows() + " dòng và " + org.springframework.util.unit.DataSize.ofBytes(props.maxMediaSize().toBytes()).toMegabytes() + " MB media mỗi lần.",
            "7. Chỉ cần MỘT dòng sai, cả lô bị từ chối và không câu hỏi nào được tạo: bạn nhận danh sách lỗi theo dòng và file Excel tô đỏ ô lỗi. Sửa rồi tải lại.",
            "8. Thành công: mọi dòng thành bản nháp (DRAFT); bạn gửi duyệt cả lô sau đó.",
            "9. Cột note không được import (dòng ví dụ ghi VÍ DỤ ở đó và bị bỏ qua). Cột points: điểm của câu (mặc định 1). Cột explanation: giải thích."
        };
        for (String line : lines) {
            s.createRow(r++).createCell(0).setCellValue(line);
        }
        r++;
        Row head = s.createRow(r++);
        String[] titles = {"Cột", "Bắt buộc", "Ý nghĩa", "Quy định"};
        for (int i = 0; i < titles.length; i++) {
            head.createCell(i).setCellValue(titles[i]);
            head.getCell(i).setCellStyle(bold);
        }
        row(s, r++, ColumnPlan.NOTE, "", "Ghi chú tự do, không import", "");
        row(s, r++, ColumnPlan.POINTS, "", "Điểm của câu hỏi", "mặc định 1; lớn hơn 0, tối đa 1000, tối đa 2 chữ số thập phân");
        row(s, r++, ColumnPlan.EXPLANATION, "", "Giải thích đáp án", "tối đa 20000 ký tự");
        for (Column c : plan.columns()) {
            row(s, r++, c.header(), c.required() ? "*" : "", (c.side() == Side.ANSWER ? "(đáp án) " : "") + c.title(), c.constraint());
        }
        s.setColumnWidth(0, 14000);
        s.setColumnWidth(1, 2800);
        s.setColumnWidth(2, 14000);
        s.setColumnWidth(3, 20000);
        for (int i = 1; i <= lines.length; i++) {
            s.getRow(i).getCell(0).setCellStyle(wrap);
        }
    }

    private static void row(Sheet s, int r, String a, String b, String c, String d) {
        Row row = s.createRow(r);
        row.createCell(0).setCellValue(a);
        row.createCell(1).setCellValue(b);
        row.createCell(2).setCellValue(c);
        row.createCell(3).setCellValue(d);
    }

    private void data(XSSFWorkbook wb, ColumnPlan plan) {
        Sheet s = wb.createSheet(DATA_SHEET);
        CellStyle head = bold(wb);
        head.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        head.setBorderBottom(BorderStyle.THIN);
        CellStyle example = wb.createCellStyle();
        example.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        example.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        List<String> headers = plan.headers();
        Row h = s.createRow(0);
        Row ex = s.createRow(1);
        for (int i = 0; i < headers.size(); i++) {
            h.createCell(i).setCellValue(headers.get(i));
            h.getCell(i).setCellStyle(head);
            ex.createCell(i).setCellStyle(example);
            s.setColumnWidth(i, Math.min(60, Math.max(14, headers.get(i).length() + 4)) * 256);
        }
        ex.getCell(0).setCellValue(ColumnPlan.EXAMPLE_MARK);
        int offset = ColumnPlan.RESERVED.size();
        DataValidationHelper dv = s.getDataValidationHelper();
        for (int i = 0; i < plan.columns().size(); i++) {
            Column c = plan.columns().get(i);
            String sample = Examples.value(c, plan);
            if (sample != null) {
                ex.getCell(offset + i).setCellValue(sample);
            }
            if (!c.allowed().isEmpty() && (!c.list() || singleValued(c))) {
                DataValidationConstraint k = dv.createExplicitListConstraint(c.allowed().toArray(String[]::new));
                DataValidation v = dv.createValidation(k, new CellRangeAddressList(1, props.maxRows(), offset + i, offset + i));
                v.setShowErrorBox(true);
                v.setErrorStyle(DataValidation.ErrorStyle.STOP);
                v.createErrorBox("Giá trị không hợp lệ", "Chọn một giá trị trong danh sách.");
                s.addValidationData(v);
            }
        }
        s.createFreezePane(0, 1);
    }

    private static CellStyle bold(XSSFWorkbook wb) {
        Font f = wb.createFont();
        f.setBold(true);
        CellStyle st = wb.createCellStyle();
        st.setFont(f);
        return st;
    }

    /** A list column whose cell may hold only one value (one accepted answer per blank): a drop-down is right for it too. */
    private static boolean singleValued(Column c) {
        return c.list() && c.constraint().contains("tối đa 1 biến thể");
    }

    /** The example row's values, derived from the schema (no per-type text): enough to show what goes where. */
    static final class Examples {

        private Examples() {}

        static String value(Column c, ColumnPlan plan) {
            if (!c.required()) {
                return null;
            }
            if (c.kind() == Kind.MEDIA) {
                String kind = c.mediaKind();
                return "AUDIO".equals(kind) ? "audio1.mp3" : "VIDEO".equals(kind) ? "video1.mp4" : "image1.png";
            }
            if (!c.allowed().isEmpty()) {
                return c.allowed().get(0);
            }
            String last = String.valueOf(c.path().get(c.path().size() - 1));
            return switch (c.kind()) {
                case BOOLEAN -> "FALSE";
                case INTEGER -> c.schema().has("minimum") ? c.schema().get("minimum").asText() : "1";
                case NUMBER -> c.schema().has("minimum") && c.schema().get("minimum").asDouble() > 0.5 ? c.schema().get("minimum").asText() : "0.5";
                case STRING, MEDIA -> c.list() || "key".equals(last) ? letter(lastIndex(c)) : text(c);
            };
        }

        /** The 0-based position of the nearest array element in the path (the option, the blank); 0 when there is none. */
        private static int lastIndex(Column c) {
            for (int i = c.path().size() - 1; i >= 0; i--) {
                if (c.path().get(i) instanceof Integer n) {
                    return n;
                }
            }
            return 0;
        }

        private static String letter(int i) {
            return String.valueOf((char) ('A' + Math.min(i, 25)));
        }

        private static String text(Column c) {
            String base = "Ví dụ: " + (c.title().isBlank() ? c.header() : c.title());
            int max = c.schema().path("maxLength").asInt(Integer.MAX_VALUE);
            return base.length() > max ? base.substring(0, max) : base;
        }
    }
}
