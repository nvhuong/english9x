package com.english9x.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.notification.api.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * An EventType is not done until the in-app list can word it too: the frontend's notifications namespace must have a title and a body for
 * every type in both languages, using the same data placeholders as the mail/Telegram template of that language.
 */
class FrontendEventTextsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode frontend(String lang) throws IOException {
        Path p = Path.of("..", "..", "frontend", "src", "i18n", "ns", "notifications." + lang + ".json").toAbsolutePath().normalize();
        assertTrue(Files.exists(p), "missing " + p);
        return JSON.readTree(Files.readString(p));
    }

    private static Properties templates(String lang) throws IOException {
        Properties p = new Properties();
        try (InputStream in = FrontendEventTextsTest.class.getResourceAsStream("/notification/templates." + lang + ".properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private static Set<String> names(String text, Pattern p) {
        Set<String> out = new TreeSet<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void everyEventTypeHasInAppTextsInBothLanguagesWithTheSamePlaceholdersAsTheTemplates() throws IOException {
        for (String lang : new String[] {"vi", "en"}) {
            JsonNode ui = frontend(lang).path("event");
            Properties mail = templates(lang);
            for (EventType t : EventType.values()) {
                for (String part : new String[] {"title", "body"}) {
                    String front = ui.path(t.name()).path(part).asText("");
                    assertFalse(front.isBlank(), lang + " in-app " + part + " of " + t);
                    assertEquals(names(mail.getProperty(t.name() + "." + part), Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}")),
                            names(front, Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}")),
                            lang + " placeholders of " + t + "." + part);
                }
            }
            Set<String> known = new TreeSet<>();
            for (EventType t : EventType.values()) {
                known.add(t.name());
            }
            Set<String> inFront = new TreeSet<>();
            ui.fieldNames().forEachRemaining(inFront::add);
            assertEquals(known, inFront, lang + ": the frontend words exactly the event types the backend has");
        }
    }
}
