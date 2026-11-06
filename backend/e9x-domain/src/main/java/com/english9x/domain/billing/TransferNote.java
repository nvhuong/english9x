package com.english9x.domain.billing;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

public final class TransferNote {
    private static final String PREFIX = "E9X";

    private TransferNote() {}

    public static String build(String orderCode, List<String> courseCodes, int maxLength) {
        StringBuilder full = new StringBuilder(PREFIX).append(' ').append(clean(orderCode));
        String orderOnly = full.toString();
        for (String c : courseCodes) {
            full.append(' ').append(clean(c));
        }
        String note = full.toString().replaceAll(" +", " ").trim();
        if (note.length() <= maxLength) {
            return note;
        }
        orderOnly = orderOnly.replaceAll(" +", " ").trim();
        if (orderOnly.length() <= maxLength) {
            return orderOnly;
        }
        throw new IllegalArgumentException("transfer note longer than " + maxLength + " even without course codes");
    }

    private static String clean(String s) {
        String t = s.replace('đ', 'd').replace('Đ', 'D');
        t = Normalizer.normalize(t, Normalizer.Form.NFD).toUpperCase(Locale.ROOT);
        return t.replaceAll("[^A-Z0-9 ]", "");
    }
}
