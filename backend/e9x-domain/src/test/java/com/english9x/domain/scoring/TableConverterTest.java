package com.english9x.domain.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

public class TableConverterTest {

    @Test
    public void convertsByLookup() {
        TableConverter c = TableConverter.of(Map.of(0, 5.0, 1, 5.0, 2, 10.0, 3, 15.0));
        assertEquals(5.0, c.convert(0), 1e-9);
        assertEquals(5.0, c.convert(1), 1e-9);
        assertEquals(15.0, c.convert(3), 1e-9);
    }

    @Test
    public void rejectsGapInKeys() {
        assertThrows(IllegalArgumentException.class, () -> TableConverter.of(Map.of(0, 1.0, 2, 2.0)));
        assertThrows(IllegalArgumentException.class, () -> TableConverter.of(Map.of(1, 1.0, 2, 2.0)));
        assertThrows(IllegalArgumentException.class, () -> TableConverter.of(Map.of()));
    }

    @Test
    public void rejectsDecreasingValues() {
        assertThrows(IllegalArgumentException.class, () -> TableConverter.of(Map.of(0, 5.0, 1, 10.0, 2, 9.0)));
    }

    @Test
    public void rejectsRawOutOfRange() {
        TableConverter c = TableConverter.of(Map.of(0, 1.0, 1, 2.0));
        assertThrows(IllegalArgumentException.class, () -> c.convert(-1));
        assertThrows(IllegalArgumentException.class, () -> c.convert(2));
        assertThrows(IllegalArgumentException.class, () -> c.convert(-0.5));
        assertThrows(IllegalArgumentException.class, () -> c.convert(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> c.convert(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> c.convert(Double.NEGATIVE_INFINITY));
    }

    private static TableConverter linear(int max) {
        java.util.Map<Integer, Double> m = new java.util.HashMap<>();
        for (int i = 0; i <= max; i++) {
            m.put(i, (double) i);
        }
        return TableConverter.of(m);
    }

    @Test
    public void nonIntegerRawIsFloored() {
        TableConverter c = linear(40);
        assertEquals(6.0, c.convert(20.0 / 3.0), 1e-9);
        assertEquals(0.0, c.convert(0.5), 1e-9);
    }

    @Test
    public void floorToleratesFloatingPointError() {
        assertEquals(40.0, linear(40).convert(39.9999999999), 1e-9);
    }

    @Test
    public void rangeCheckAppliesToFlooredValue() {
        assertEquals(40.0, linear(40).convert(40.5), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> linear(40).convert(41.0));
    }
}
