package com.english9x.domain.billing;

/** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no final XOR. */
public final class Crc16Ccitt {
    private Crc16Ccitt() {}

    public static int compute(String ascii) {
        int crc = 0xFFFF;
        for (int i = 0; i < ascii.length(); i++) {
            crc ^= (ascii.charAt(i) & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }
        return crc;
    }
}
