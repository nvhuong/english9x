package com.english9x.platform.jobs;

import java.util.regex.Pattern;

/** Job type names double as configuration key segments (e9x.jobs.&lt;type&gt;.retry-delays), so they are kept simple. */
final class JobTypes {

    private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    private JobTypes() {}

    static String validate(String type) {
        if (type == null || !VALID.matcher(type).matches()) {
            throw new IllegalArgumentException("invalid job type '" + type + "': expected [a-z][a-z0-9_]{0,63}");
        }
        return type;
    }
}
