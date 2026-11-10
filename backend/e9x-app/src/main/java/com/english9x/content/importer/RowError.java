package com.english9x.content.importer;

/**
 * One problem of the file.
 *
 * @param row    the row of the sheet as Excel shows it (1 = the header, data from 2); 0 = the file as a whole
 * @param column the header of the column, or {@code null} when it is the row (or the file) as a whole
 */
record RowError(int row, String column, String message) {

    /** The sentence a person reads: {@code dòng 37: thiếu đáp án}. */
    String display() {
        return row == 0 ? message : "dòng " + row + ": " + message;
    }
}
