package com.english9x.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.notification.api.EventType;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Every EventType has a title and a body in both languages, and the two language files carry the same keys. */
class TemplatesTest {

    private static Properties load(String lang) throws Exception {
        Properties p = new Properties();
        try (InputStream in = TemplatesTest.class.getResourceAsStream("/notification/templates." + lang + ".properties")) {
            assertTrue(in != null, "missing templates." + lang + ".properties");
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void everyEventTypeHasATitleAndABodyInViAndEn() throws Exception {
        for (String lang : new String[] {"vi", "en"}) {
            Properties p = load(lang);
            for (EventType t : EventType.values()) {
                assertFalse(p.getProperty(t.name() + ".title", "").isBlank(), lang + " title of " + t);
                assertFalse(p.getProperty(t.name() + ".body", "").isBlank(), lang + " body of " + t);
            }
        }
    }

    @Test
    void theLanguageFilesHaveTheSameKeys() throws Exception {
        assertEquals(load("vi").stringPropertyNames(), load("en").stringPropertyNames());
    }

    @Test
    void noTemplateKeyBelongsToAnUnknownEventType() throws Exception {
        for (String key : load("vi").stringPropertyNames()) {
            if (key.startsWith("email.") || key.startsWith("telegram.")) {
                continue;
            }
            String type = key.substring(0, key.lastIndexOf('.'));
            EventType.valueOf(type);
        }
    }
}
