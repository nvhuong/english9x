package com.english9x.billing.order;

import java.security.SecureRandom;

/**
 * Where order codes come from. The default is random over the capital letters and digits that cannot be misread when typed off a bank screen
 * (no 0/O, 1/I); every code it makes passes {@code Codes.isValidOrderCode}. Uniqueness is NOT promised here: the unique index of
 * {@code orders.code} is the arbiter and {@link OrderService} retries on a collision. A bean of this type replaces the default (tests use that).
 */
interface OrderCodes {

    String next();

    final class Random implements OrderCodes {
        private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
        private final SecureRandom random = new SecureRandom();

        @Override
        public String next() {
            char[] c = new char[6];
            for (int i = 0; i < c.length; i++) {
                c[i] = ALPHABET[random.nextInt(ALPHABET.length)];
            }
            return new String(c);
        }
    }
}
