package com.english9x.notification.internal;

import com.english9x.notification.api.EventType;
import com.english9x.notification.api.Message;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Message texts in Vietnamese and English (classpath {@code notification/templates.<lang>.properties}). The recipient chooses the
 * language ({@code users.locale}): anything but "en" is Vietnamese. A template is trusted text; {@code {key}} is replaced from the
 * event data in ONE pass (a value that itself contains {@code {other}} is not expanded again) and every value is flattened to a single
 * line. Output is plain text: channels escape it for their own format, see {@link Escape}.
 */
final class Templates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");

    private final Properties vi = load("vi");
    private final Properties en = load("en");
    private final String baseUrl;

    Templates(String publicBaseUrl) {
        this.baseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    private static Properties load(String lang) {
        Properties p = new Properties();
        try (InputStream in = Templates.class.getResourceAsStream("/notification/templates." + lang + ".properties")) {
            if (in == null) {
                throw new IllegalStateException("missing notification templates for " + lang);
            }
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    static boolean english(String locale) {
        return "en".equals(locale);
    }

    private Properties of(String locale) {
        return english(locale) ? en : vi;
    }

    /** A fixed text (greeting, footer, bot reply). */
    String text(String key, String locale) {
        String v = of(locale).getProperty(key);
        if (v == null) {
            throw new IllegalStateException("missing template key " + key);
        }
        return v;
    }

    /** A fixed text with {@code {name}}-style values. */
    String text(String key, String locale, Map<String, ?> values) {
        return fill(text(key, locale), values);
    }

    /** The rendered message of an event for a recipient who reads {@code locale}. */
    Message render(EventType type, Map<String, ?> data, String locale) {
        return new Message(type, fill(text(type.name() + ".title", locale), data), fill(text(type.name() + ".body", locale), data),
                baseUrl + "/notifications");
    }

    /** Single pass; a missing key becomes empty; values are flattened to one line (no control characters). */
    static String fill(String template, Map<String, ?> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(template, last, m.start());
            Object v = values.get(m.group(1));
            out.append(v == null ? "" : oneLine(String.valueOf(v)));
            last = m.end();
        }
        return out.append(template, last, template.length()).toString();
    }

    private static final Pattern BREAKS = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029]+");

    /** One line: every run of control characters (CR, LF, tab, NEL ...) and line/paragraph separators becomes a single space. */
    static String oneLine(String s) {
        return BREAKS.matcher(s).replaceAll(" ").strip();
    }
}
