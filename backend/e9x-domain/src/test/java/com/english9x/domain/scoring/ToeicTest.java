package com.english9x.domain.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class ToeicTest {

    @Test
    public void totalSumsSections() {
        assertEquals(990, Toeic.total(495, 495));
        assertEquals(10, Toeic.total(5, 5));
        assertEquals(700, Toeic.total(350, 350));
    }

    @Test
    public void rejectsNonMultipleOfFive() {
        assertThrows(IllegalArgumentException.class, () -> Toeic.total(101, 100));
        assertThrows(IllegalArgumentException.class, () -> Toeic.total(100, 102));
    }

    @Test
    public void rejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> Toeic.total(0, 100));
        assertThrows(IllegalArgumentException.class, () -> Toeic.total(100, 500));
        assertThrows(IllegalArgumentException.class, () -> Toeic.total(-5, 100));
    }
}
