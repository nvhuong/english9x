package com.english9x.analytics.reports;

import com.english9x.analytics.reports.ReportsDtos.ClassReport;
import com.english9x.analytics.reports.ReportsDtos.CourseLine;
import com.english9x.analytics.reports.ReportsDtos.LearnerRow;
import com.english9x.analytics.reports.ReportsDtos.ScoreLine;
import com.english9x.analytics.reports.ReportsDtos.WeakType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * The class report as an .xlsx (Apache POI): ONE sheet named after the class, a header row, then one row per learner of the {@link ClassReport}. It is built from the same object as the
 * JSON and formats nothing of its own: a number is a number cell holding the very value of the JSON, a missing figure is an empty cell, the lists (courses, weak types) are one text cell.
 * Columns are listed once, in {@link #COLUMNS}; the tests read the file back with POI and compare cell by cell.
 */
final class ReportWorkbook {

    private ReportWorkbook() {}

    /** The words of the file; vi (default) or en. */
    record Labels(String lang, Map<String, String> text) {

        static Labels of(String lang) {
            return "en".equalsIgnoreCase(lang) ? new Labels("en", EN) : new Labels("vi", VI);
        }

        String get(String key) {
            return text.getOrDefault(key, key);
        }
    }

    // column keys, in order
    static final List<String> COLUMNS = List.of("learner", "email", "progress", "courses", "submitted", "late", "missing", "pending", "latest", "latestKind", "best", "bestKind",
            "weak", "attendance", "present", "lateAtt", "absent", "excused", "minutes");

    private static final Map<String, String> VI = Map.ofEntries(
            Map.entry("learner", "Học viên"), Map.entry("email", "Email"), Map.entry("progress", "Tiến độ khoá học (%)"), Map.entry("courses", "Khoá học"),
            Map.entry("submitted", "Bài đã nộp"), Map.entry("late", "Bài nộp trễ"), Map.entry("missing", "Bài chưa nộp (quá hạn)"), Map.entry("pending", "Bài chưa đến hạn"),
            Map.entry("latest", "Điểm gần nhất"), Map.entry("latestKind", "Loại điểm gần nhất"), Map.entry("best", "Điểm tốt nhất"), Map.entry("bestKind", "Loại điểm tốt nhất"),
            Map.entry("weak", "Dạng câu yếu"), Map.entry("attendance", "Chuyên cần"), Map.entry("present", "Có mặt"), Map.entry("lateAtt", "Đi trễ"), Map.entry("absent", "Vắng"),
            Map.entry("excused", "Có phép"), Map.entry("minutes", "Phút học"), Map.entry("PRELIMINARY", "Tạm thời"), Map.entry("OFFICIAL", "Chính thức"));

    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("learner", "Learner"), Map.entry("email", "Email"), Map.entry("progress", "Course progress (%)"), Map.entry("courses", "Courses"),
            Map.entry("submitted", "Handed in"), Map.entry("late", "Handed in late"), Map.entry("missing", "Not handed in (overdue)"), Map.entry("pending", "Not yet due"),
            Map.entry("latest", "Latest score"), Map.entry("latestKind", "Latest score label"), Map.entry("best", "Best score"), Map.entry("bestKind", "Best score label"),
            Map.entry("weak", "Weak question types"), Map.entry("attendance", "Attendance"), Map.entry("present", "Present"), Map.entry("lateAtt", "Late"), Map.entry("absent", "Absent"),
            Map.entry("excused", "Excused"), Map.entry("minutes", "Study minutes"), Map.entry("PRELIMINARY", "Preliminary"), Map.entry("OFFICIAL", "Official"));

    /** Excel allows 31 characters and none of []:*?/\ in a sheet name. */
    static String sheetName(String className) {
        String n = className == null ? "" : className.replaceAll("[\\[\\]:*?/\\\\]", " ").strip();
        if (n.isEmpty()) {
            n = "Class";
        }
        return n.length() > 31 ? n.substring(0, 31).strip() : n;
    }

    /** The text of the courses cell; the tests build the expected text with the same function. */
    static String coursesText(List<CourseLine> courses) {
        return courses.stream().map(c -> (c.code() == null ? c.title() : c.code() + " " + c.title()) + " " + c.percent() + "%").collect(Collectors.joining("; "));
    }

    static String weakText(List<WeakType> weak) {
        return weak.stream().map(w -> w.typeName() + " (" + w.correct() + "/" + w.total() + ")").collect(Collectors.joining("; "));
    }

    static byte[] write(ClassReport report, Labels labels) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName(report.className()));
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            head.setWrapText(true);
            CellStyle percent = wb.createCellStyle();
            percent.setDataFormat(wb.createDataFormat().getFormat("0%"));
            Row header = sheet.createRow(0);
            for (int c = 0; c < COLUMNS.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(labels.get(COLUMNS.get(c)));
                cell.setCellStyle(head);
            }
            int r = 1;
            for (LearnerRow l : report.learners()) {
                Row row = sheet.createRow(r++);
                int c = 0;
                text(row, c++, l.displayName());
                text(row, c++, l.email());
                number(row, c++, l.progressPercent());
                text(row, c++, coursesText(l.courses()));
                number(row, c++, l.assignments().submitted());
                number(row, c++, l.assignments().late());
                number(row, c++, l.assignments().missing());
                number(row, c++, l.assignments().pending());
                ScoreLine latest = l.latestScore();
                number(row, c++, latest == null ? null : latest.value());
                text(row, c++, latest == null ? null : labels.get(latest.kind()));
                ScoreLine best = l.bestScore();
                number(row, c++, best == null ? null : best.value());
                text(row, c++, best == null ? null : labels.get(best.kind()));
                text(row, c++, weakText(l.weakTypes()));
                Cell rate = number(row, c++, l.attendance().rate());
                if (rate != null) {
                    rate.setCellStyle(percent);
                }
                number(row, c++, l.attendance().present());
                number(row, c++, l.attendance().late());
                number(row, c++, l.attendance().absent());
                number(row, c++, l.attendance().excused());
                number(row, c, l.studyMinutes());
            }
            sheet.createFreezePane(1, 1);
            for (int c = 0; c < COLUMNS.size(); c++) {
                sheet.setColumnWidth(c, (c == 0 || c == 3 || c == 12 ? 32 : 16) * 256); // fixed widths: autoSizeColumn needs AWT fonts, which a server image may not have
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void text(Row row, int col, String value) {
        if (value != null && !value.isEmpty()) {
            row.createCell(col).setCellValue(value); // a string cell: a spreadsheet never runs it as a formula
        }
    }

    private static Cell number(Row row, int col, Number value) {
        if (value == null) {
            return null;
        }
        Cell cell = row.createCell(col);
        cell.setCellValue(value.doubleValue());
        return cell;
    }
}
