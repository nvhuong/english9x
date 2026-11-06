package com.english9x.domain.billing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

public class VietQrTest {
    private static VietQrRequest req() { return new VietQrRequest("970436", "0011001234567", 150000, "E9X D2611A IELW01"); }

    @Test
    public void buildsExpectedTopLevelFields() {
        Map<String, String> f = VietQr.parseTopLevel(VietQr.payload(req()));
        assertEquals("01", f.get("00"));
        assertEquals("12", f.get("01"));
        assertEquals("704", f.get("53"));
        assertEquals("150000", f.get("54"));
        assertEquals("VN", f.get("58"));
    }

    @Test
    public void crcMatchesBody() {
        String p = VietQr.payload(req());
        String body = p.substring(0, p.length() - 4);
        assertTrue(body.endsWith("6304"));
        assertEquals(String.format("%04X", Crc16Ccitt.compute(body)), p.substring(p.length() - 4));
    }

    @Test
    public void merchantInfoContainsBinAndAccount() {
        Map<String, String> f = VietQr.parseTopLevel(VietQr.payload(req()));
        Map<String, String> m = VietQr.parseTopLevel(f.get("38"));
        assertEquals("A000000727", m.get("00"));
        assertEquals("QRIBFTTA", m.get("02"));
        Map<String, String> acc = VietQr.parseTopLevel(m.get("01"));
        assertEquals("970436", acc.get("00"));
        assertEquals("0011001234567", acc.get("01"));
    }

    @Test
    public void noteInField62() {
        Map<String, String> f = VietQr.parseTopLevel(VietQr.payload(req()));
        assertEquals("E9X D2611A IELW01", VietQr.parseTopLevel(f.get("62")).get("08"));
    }

    @Test
    public void rejectsBadBin() {
        assertThrows(IllegalArgumentException.class, () -> new VietQrRequest("97043", "1", 1, "x"));
        assertThrows(IllegalArgumentException.class, () -> new VietQrRequest("97043a", "1", 1, "x"));
    }

    @Test
    public void rejectsNonPositiveAmount() {
        assertThrows(IllegalArgumentException.class, () -> new VietQrRequest("970436", "1", 0, "x"));
        assertThrows(IllegalArgumentException.class, () -> new VietQrRequest("970436", "1", -5, "x"));
    }

    @Test
    public void rejectsNonAsciiNote() {
        assertThrows(IllegalArgumentException.class, () -> new VietQrRequest("970436", "1", 1, "đề thi"));
    }
}
