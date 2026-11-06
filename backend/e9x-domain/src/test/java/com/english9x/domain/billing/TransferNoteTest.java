package com.english9x.domain.billing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class TransferNoteTest {
    @Test
    public void joinsOrderAndCourseCodes() {
        assertEquals("E9X D2611A IELW01 TOEL34", TransferNote.build("D2611A", List.of("IELW01", "TOEL34"), 50));
    }

    @Test
    public void stripsVietnameseDiacritics() {
        assertEquals("E9X D2611A DE01X", TransferNote.build("D2611A", List.of("đề01x"), 50));
    }

    @Test
    public void fallsBackToOrderCodeWhenTooLong() {
        List<String> codes = List.of("AAAAA1", "AAAAA2", "AAAAA3", "AAAAA4", "AAAAA5", "AAAAA6", "AAAAA7", "AAAAA8");
        assertEquals("E9X D2611A", TransferNote.build("D2611A", codes, 50));
    }

    @Test
    public void rejectsWhenEvenOrderCodeTooLong() {
        assertThrows(IllegalArgumentException.class, () -> TransferNote.build("D2611A", List.of("IELW01"), 5));
    }
}
