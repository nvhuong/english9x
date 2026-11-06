package com.english9x.domain.billing;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds a static VietQR (EMVCo merchant-presented) payload for NAPAS 247 transfers. */
public final class VietQr {
    private VietQr() {}

    public static String payload(VietQrRequest r) {
        String account = tlv("00", r.bankBin()) + tlv("01", r.accountNo());
        String merchant = tlv("00", "A000000727") + tlv("01", account) + tlv("02", "QRIBFTTA");
        String body = tlv("00", "01")
                + tlv("01", "12")
                + tlv("38", merchant)
                + tlv("53", "704")
                + tlv("54", Long.toString(r.amountVnd()))
                + tlv("58", "VN")
                + tlv("62", tlv("08", r.note()))
                + "6304";
        return body + String.format("%04X", Crc16Ccitt.compute(body));
    }

    public static Map<String, String> parseTopLevel(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        int i = 0;
        while (i < payload.length()) {
            if (i + 4 > payload.length()) {
                throw new IllegalArgumentException("truncated TLV header at " + i);
            }
            String id = payload.substring(i, i + 2);
            int len;
            try {
                len = Integer.parseInt(payload.substring(i + 2, i + 4));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad TLV length at " + i, e);
            }
            if (len < 0 || i + 4 + len > payload.length()) {
                throw new IllegalArgumentException("TLV value overruns payload at " + i);
            }
            fields.put(id, payload.substring(i + 4, i + 4 + len));
            i += 4 + len;
        }
        return fields;
    }

    private static String tlv(String id, String value) {
        if (value.length() > 99) {
            throw new IllegalArgumentException("TLV value too long for field " + id);
        }
        return id + String.format("%02d", value.length()) + value;
    }
}
