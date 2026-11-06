package org.junit.jupiter.api;

import java.util.Objects;
import org.junit.jupiter.api.function.Executable;

/** Offline shim of the subset of JUnit 5 Assertions allowed by the plan's Global Constraints. */
public final class Assertions {
    private Assertions() {}

    public static void assertEquals(Object expected, Object actual) { assertEquals(expected, actual, null); }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            fail(message, "expected: <" + expected + "> but was: <" + actual + ">");
        }
    }

    public static void assertEquals(long expected, long actual) {
        if (expected != actual) fail(null, "expected: <" + expected + "> but was: <" + actual + ">");
    }

    public static void assertEquals(double expected, double actual, double delta) {
        if (Math.abs(expected - actual) > delta) {
            fail(null, "expected: <" + expected + "> but was: <" + actual + "> (delta " + delta + ")");
        }
    }

    public static void assertTrue(boolean condition) { assertTrue(condition, null); }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) fail(message, "expected: <true> but was: <false>");
    }

    public static void assertFalse(boolean condition) { assertFalse(condition, null); }

    public static void assertFalse(boolean condition, String message) {
        if (condition) fail(message, "expected: <false> but was: <true>");
    }

    public static void assertNull(Object actual) {
        if (actual != null) fail(null, "expected: <null> but was: <" + actual + ">");
    }

    public static void assertNotNull(Object actual) {
        if (actual == null) fail(null, "expected: not <null>");
    }

    public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable) {
        try {
            executable.execute();
        } catch (Throwable actual) {
            if (expectedType.isInstance(actual)) return expectedType.cast(actual);
            AssertionError error = new AssertionError("Unexpected exception type thrown, expected: <"
                    + expectedType.getName() + "> but was: <" + actual.getClass().getName() + ">");
            error.initCause(actual);
            throw error;
        }
        throw new AssertionError("Expected " + expectedType.getName() + " to be thrown, but nothing was thrown.");
    }

    private static void fail(String message, String detail) {
        throw new AssertionError(message == null ? detail : message + " ==> " + detail);
    }
}
