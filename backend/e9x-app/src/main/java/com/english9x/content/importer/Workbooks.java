package com.english9x.content.importer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Reading the author's workbook, and writing it back with the cells that failed marked red. */
final class Workbooks {

    private Workbooks() {}

    /** The header row and the data rows of sheet {@code Du_lieu} (the first sheet when that name is not there), as text by lower-case header. */
    record Sheet2(List<String> headers, List<DataRow> rows, int totalRows) {}

    /** {@code number}: the row as Excel shows it (data starts at 2). */
    record DataRow(int number, Map<String, String> cells) {}

    /** The workbook cannot be used as a whole (not an xlsx, no header, too many rows): the message is for the author. */
    static final class Unusable extends Exception {
        Unusable(String message) {
            super(message);
        }
    }

    static Sheet2 read(Path file, int maxRows) throws Unusable, IOException {
        try (OPCPackage pkg = open(file); XSSFWorkbook wb = new XSSFWorkbook(pkg)) {
            Sheet sheet = wb.getSheet(TemplateGenerator.DATA_SHEET);
            if (sheet == null) {
                sheet = wb.getNumberOfSheets() == 0 ? null : wb.getSheetAt(0);
            }
            if (sheet == null) {
                throw new Unusable("không có sheet nào trong file Excel");
            }
            Row head = sheet.getRow(sheet.getFirstRowNum());
            if (head == null || sheet.getFirstRowNum() != 0) {
                throw new Unusable("dòng 1 của sheet " + sheet.getSheetName() + " phải là dòng tiêu đề cột");
            }
            DataFormatter fmt = new DataFormatter(Locale.ROOT);
            List<String> headers = new ArrayList<>();
            for (int c = 0; c < head.getLastCellNum(); c++) {
                headers.add(head.getCell(c) == null ? "" : fmt.formatCellValue(head.getCell(c)).strip());
            }
            List<DataRow> rows = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, String> cells = new LinkedHashMap<>();
                boolean any = false;
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.getCell(c);
                    if (cell == null || headers.get(c).isEmpty()) {
                        continue;
                    }
                    String text;
                    if (cell.getCellType() == CellType.FORMULA) {
                        text = "=";
                    } else {
                        text = fmt.formatCellValue(cell).strip();
                    }
                    if (!text.isEmpty()) {
                        cells.put(headers.get(c).toLowerCase(Locale.ROOT), text);
                        any = true;
                    }
                }
                if (!any) {
                    continue;
                }
                if (ColumnPlan.EXAMPLE_MARK.equalsIgnoreCase(cells.getOrDefault(ColumnPlan.NOTE, ""))) {
                    continue;
                }
                if (rows.size() >= maxRows) {
                    throw new Unusable("file có hơn " + maxRows + " dòng dữ liệu; giới hạn mỗi lô là " + maxRows + " dòng");
                }
                rows.add(new DataRow(r + 1, cells));
            }
            return new Sheet2(List.copyOf(headers), List.copyOf(rows), rows.size());
        } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException | RuntimeException e) {
            throw new Unusable("không đọc được file Excel (.xlsx)");
        }
    }

    private static OPCPackage open(Path file) throws IOException, org.apache.poi.openxml4j.exceptions.InvalidFormatException {
        return OPCPackage.open(file.toFile(), PackageAccess.READ);
    }

    /**
     * Copies the author's workbook to {@code out} with the cells that failed filled red (the whole row, lightly, when the error is about the row), and a last
     * column {@code loi} on the data sheet that says what is wrong in each row.
     */
    static void writeReport(Path original, List<RowError> errors, OutputStream out) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(original); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Sheet found = wb.getSheet(TemplateGenerator.DATA_SHEET);
            Sheet sheet = found != null ? found : wb.getSheetAt(0);
            CellStyle red = wb.createCellStyle();
            red.setFillForegroundColor(IndexedColors.RED.getIndex());
            red.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle pink = wb.createCellStyle();
            pink.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            pink.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Row head = sheet.getRow(0);
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (int c = 0; head != null && c < head.getLastCellNum(); c++) {
                if (head.getCell(c) != null) {
                    columns.put(head.getCell(c).getStringCellValue().strip().toLowerCase(Locale.ROOT), c);
                }
            }
            int messageColumn = Math.max(head == null ? 0 : head.getLastCellNum(), 0);
            if (head != null) {
                head.createCell(messageColumn).setCellValue("loi");
            }
            Map<Integer, StringBuilder> perRow = new LinkedHashMap<>();
            for (RowError e : errors) {
                if (e.row() < 1) {
                    continue;
                }
                Row row = sheet.getRow(e.row() - 1);
                if (row == null) {
                    row = sheet.createRow(e.row() - 1);
                }
                Integer col = e.column() == null ? null : columns.get(e.column().toLowerCase(Locale.ROOT));
                if (col == null) {
                    for (int c = 0; c < messageColumn; c++) {
                        Cell cell = row.getCell(c) == null ? row.createCell(c) : row.getCell(c);
                        cell.setCellStyle(pink);
                    }
                } else {
                    Cell cell = row.getCell(col) == null ? row.createCell(col) : row.getCell(col);
                    cell.setCellStyle(red);
                }
                StringBuilder sb = perRow.computeIfAbsent(e.row(), k -> new StringBuilder());
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(e.column() == null ? "" : e.column() + ": ").append(e.message());
            }
            perRow.forEach((r, text) -> {
                String t = text.length() > 30000 ? text.substring(0, 30000) : text.toString();
                sheet.getRow(r - 1).createCell(messageColumn).setCellValue(t);
            });
            sheet.setColumnWidth(messageColumn, 60 * 256);
            wb.write(out);
        }
    }
}
