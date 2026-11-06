package com.english9x.domain.billing;

public record VietQrRequest(String bankBin, String accountNo, long amountVnd, String note) {
    public static final long MAX_AMOUNT_VND = 9_999_999_999_999L;
    public static final int MAX_NOTE_LENGTH = 99;

    public VietQrRequest {
        if (bankBin == null || !bankBin.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("bankBin must be exactly 6 digits");
        }
        if (accountNo == null || !accountNo.matches("[A-Za-z0-9]{1,19}")) {
            throw new IllegalArgumentException("accountNo must be 1-19 alphanumeric characters");
        }
        if (amountVnd <= 0 || amountVnd > MAX_AMOUNT_VND) {
            throw new IllegalArgumentException("amountVnd out of range");
        }
        if (note == null || note.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("note missing or longer than " + MAX_NOTE_LENGTH);
        }
        for (int i = 0; i < note.length(); i++) {
            char c = note.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new IllegalArgumentException("note must be printable ASCII");
            }
        }
    }
}
