package com.english9x.domain.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class IeltsTest {

    @Test
    public void overallRoundsQuarterUp() {
        assertEquals(6.5, Ielts.overall(6.5, 6.5, 5.0, 7.0), 1e-9);
    }

    @Test
    public void overallRoundsThreeQuartersUp() {
        assertEquals(7.0, Ielts.overall(6.5, 6.5, 7.0, 7.0), 1e-9);
    }

    @Test
    public void overallBelowQuarterRoundsDown() {
        assertEquals(6.0, Ielts.overall(6.0, 6.0, 6.0, 6.5), 1e-9);
    }

    @Test
    public void writingBandWeighted() {
        assertEquals(6.5, Ielts.writingBand(6.0, 7.0), 1e-9);
        assertEquals(7.0, Ielts.writingBand(7.0, 7.0), 1e-9);
    }

    @Test
    public void rejectsInvalidBand() {
        assertThrows(IllegalArgumentException.class, () -> Ielts.requireBand(9.5));
        assertThrows(IllegalArgumentException.class, () -> Ielts.requireBand(6.3));
        assertThrows(IllegalArgumentException.class, () -> Ielts.overall(9.5, 6, 6, 6));
        assertThrows(IllegalArgumentException.class, () -> Ielts.writingBand(6.0, 6.3));
        Ielts.requireBand(0);
        Ielts.requireBand(9);
    }
}
