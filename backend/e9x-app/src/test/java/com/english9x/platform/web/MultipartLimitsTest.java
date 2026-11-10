package com.english9x.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** spring.servlet.multipart.* defaulted to 1 MB, which refused every upload (P1 final review): the ceiling now follows the two keys of spec 15.1. */
class MultipartLimitsTest {

    @Test
    void aFileMayBeAsLargeAsTheLargerOfTheTwoKeys() {
        MultipartConfigElement e = MultipartLimits.element(DataSize.ofMegabytes(200), DataSize.ofMegabytes(500));
        assertEquals(DataSize.ofMegabytes(500).toBytes(), e.getMaxFileSize());
        e = MultipartLimits.element(DataSize.ofMegabytes(560), DataSize.ofMegabytes(500));
        assertEquals(DataSize.ofMegabytes(560).toBytes(), e.getMaxFileSize());
    }

    @Test
    void theRequestHoldsTheFileAndItsEnvelopeAndStaysBelowCaddysCeiling() {
        MultipartConfigElement e = MultipartLimits.element(DataSize.ofMegabytes(200), DataSize.ofMegabytes(500));
        assertTrue(e.getMaxRequestSize() > e.getMaxFileSize(), "an Excel workbook travels with the ZIP");
        assertTrue(e.getMaxRequestSize() <= DataSize.ofMegabytes(600).toBytes(), "Caddy's @upload limit is 600MB: the application's own ceiling must be below it");
    }

    @Test
    void nothingIsWrittenToDiskBeforeTheLimitIsKnownToBeMet() {
        MultipartConfigElement e = MultipartLimits.element(DataSize.ofMegabytes(200), DataSize.ofMegabytes(500));
        assertEquals(0, e.getFileSizeThreshold(), "every file part goes to a temp file, never held in memory");
    }

    @Test
    void keysThatWouldOverflowCaddyAreRefusedAtStartup() {
        assertThrows(IllegalStateException.class, () -> MultipartLimits.element(DataSize.ofMegabytes(200), DataSize.ofMegabytes(590)));
    }
}
