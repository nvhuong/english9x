package com.english9x.tools;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Minimal test runner for environments without network access.
 * Usage: java -cp <classes> com.english9x.tools.OfflineRunner <test-classes-dir>
 * Runs every public-or-package no-arg class whose name ends with "Test" and every method annotated with @Test.
 */
public final class OfflineRunner {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        List<String> classNames = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith("Test.class"))
                 .sorted(Comparator.naturalOrder())
                 .forEach(p -> classNames.add(root.relativize(p).toString()
                         .replace(java.io.File.separatorChar, '.').replaceAll("\\.class$", "")));
        }
        int passed = 0;
        List<String> failures = new ArrayList<>();
        for (String name : classNames) {
            Class<?> type = Class.forName(name);
            if (Modifier.isAbstract(type.getModifiers())) continue;
            List<Method> tests = new ArrayList<>();
            for (Method m : type.getDeclaredMethods()) if (m.isAnnotationPresent(Test.class)) tests.add(m);
            tests.sort(Comparator.comparing(Method::getName));
            for (Method m : tests) {
                String id = type.getSimpleName() + "." + m.getName();
                try {
                    var ctor = type.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    Object instance = ctor.newInstance();
                    m.setAccessible(true);
                    m.invoke(instance);
                    passed++;
                    System.out.println("PASS " + id);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    failures.add(id + " -> " + cause);
                    System.out.println("FAIL " + id + " -> " + cause);
                    for (StackTraceElement el : cause.getStackTrace()) {
                        if (el.getClassName().startsWith("com.english9x")) System.out.println("     at " + el);
                    }
                }
            }
        }
        System.out.println();
        System.out.println("Tests: " + (passed + failures.size()) + ", passed: " + passed + ", failed: " + failures.size());
        if (passed + failures.size() == 0) {
            System.out.println("No tests found.");
            System.exit(2);
        }
        System.exit(failures.isEmpty() ? 0 : 1);
    }
}
