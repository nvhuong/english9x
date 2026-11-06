package com.english9x.domain.billing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class Crc16CcittTest {
    @Test
    public void knownCheckValue() { assertEquals(0x29B1L, (long) Crc16Ccitt.compute("123456789")); }

    @Test
    public void emptyIsInit() { assertEquals(0xFFFFL, (long) Crc16Ccitt.compute("")); }
}
