package org.junit.jupiter.api.function;

/** Offline shim of JUnit 5 {@code Executable}. */
@FunctionalInterface
public interface Executable {
    void execute() throws Throwable;
}
