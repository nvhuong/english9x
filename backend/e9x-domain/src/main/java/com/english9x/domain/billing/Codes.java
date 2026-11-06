package com.english9x.domain.billing;

public final class Codes {
    private Codes() {}

    public static boolean isValidOrderCode(String s) { return s != null && s.matches("[A-Z0-9]{6}"); }

    public static boolean isValidCourseCode(String s) { return s != null && s.matches("[A-Z0-9]{6}"); }
}
