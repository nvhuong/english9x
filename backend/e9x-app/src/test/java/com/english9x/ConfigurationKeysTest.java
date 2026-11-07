package com.english9x;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** Spec §15.1: every self-set parameter is a configuration key carrying its documented default. */
class ConfigurationKeysTest {

    private static PropertySource<?> load() throws Exception {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        return sources.get(0);
    }

    @Test
    void everySpecKeyHasItsDefault() throws Exception {
        PropertySource<?> p = load();
        String[][] expected = {
            {"e9x.grading.ai.per-user-daily-limit", "0"},
            {"e9x.grading.ai.retry-delays", "30s,2m,10m"},
            {"e9x.grading.ai.schema-retry", "1"},
            {"e9x.grading.claim-timeout", "24h"},
            {"e9x.exam.default-max-resumes", "2"},
            {"e9x.exam.autosave-interval", "5s"},
            {"e9x.exam.late-sync-grace", "2m"},
            {"e9x.billing.order-expiry", "7d"},
            {"e9x.billing.transfer-note-max-length", "50"},
            {"e9x.billing.expiry-reminder", "7d"},
            {"e9x.content.import.max-rows", "2000"},
            {"e9x.content.import.max-media-size", "500MB"},
            {"e9x.media.url-ttl", "10m"},
            {"e9x.auth.access-token-ttl", "15m"},
            {"e9x.auth.refresh-token-ttl", "30d"},
            {"e9x.auth.login-rate-limit", "10/15m"},
            {"e9x.learning.streak.min-minutes", "10"},
            {"e9x.learning.heartbeat-interval", "30s"},
            {"e9x.learning.idle-timeout", "2m"},
            {"e9x.notification.session-reminder", "1h"},
            {"e9x.backup.retention", "14"},
            {"e9x.grading.ai.daily-cost-alert", "500000"},
            {"e9x.gemini.model", "gemini-2.5-flash"},
            {"e9x.gemini.timeout", "60s"},
            {"e9x.content.media.max-upload-size", "200MB"},
            {"e9x.org.invitation-ttl", "7d"},
            {"e9x.org.invitation-hourly-limit", "50"},
            {"e9x.workflow.max-bundle-size", "2000"},
            {"e9x.audit.default-page-size", "50"},
            {"e9x.audit.max-page-size", "100"},
            {"e9x.audit.max-detail-bytes", "4096"},
            {"e9x.events.redelivery-after", "2m"},
            {"e9x.events.publication-max-age", "30d"},
            {"spring.modulith.events.republish-outstanding-events-on-restart", "false"},
            {"e9x.notification.telegram.api-base", "${E9X_TELEGRAM_API_BASE:https://api.telegram.org}"},
            {"e9x.notification.telegram.link-ttl", "15m"},
        };
        for (String[] kv : expected) {
            Object actual = p.getProperty(kv[0]);
            assertEquals(kv[1], String.valueOf(actual), "default of " + kv[0]);
        }
    }

    @Test
    void telegramSecretsHaveNoDefaultAndTheBotIsOffUntilConfigured() throws Exception {
        PropertySource<?> p = load();
        assertEquals("${E9X_TELEGRAM_BOT_TOKEN:}", String.valueOf(p.getProperty("e9x.notification.telegram.bot-token")), "the bot token has no default value");
        assertEquals("${E9X_TELEGRAM_WEBHOOK_SECRET:}", String.valueOf(p.getProperty("e9x.notification.telegram.webhook-secret")),
                "an empty secret makes the webhook refuse every call");
        assertEquals("${E9X_TELEGRAM_BOT_USERNAME:}", String.valueOf(p.getProperty("e9x.notification.telegram.bot-username")));
        assertEquals("delete", String.valueOf(p.getProperty("spring.modulith.events.completion-mode")), "completed event publications do not linger");
    }

    @Test
    void geminiPricesAreConfigurableKeys() throws Exception {
        PropertySource<?> p = load();
        for (String key : new String[] {"e9x.gemini.price-per-1k-input", "e9x.gemini.price-per-1k-output"}) {
            assertEquals(true, p.containsProperty(key), key + " must exist");
        }
    }

    @Test
    void jobQueueKeysExistAndAiGradeKeepsASingleSourceOfRetryDelays() throws Exception {
        PropertySource<?> p = load();
        assertEquals("2s", String.valueOf(p.getProperty("e9x.jobs.poll-interval")));
        assertEquals("10m", String.valueOf(p.getProperty("e9x.jobs.lease")));
        assertEquals("30s,2m,10m", String.valueOf(p.getProperty("e9x.jobs.default-retry-delays")));
        assertEquals("30m", String.valueOf(p.getProperty("e9x.jobs.max-run")), "heartbeat is capped by max-run");
        assertEquals("auto_submit_attempt", String.valueOf(p.getProperty("e9x.jobs.priority-types")));
        assertEquals(false, p.containsProperty("e9x.jobs.ai_grade.retry-delays"),
                "ai_grade resolves to e9x.grading.ai.retry-delays; its value must not be duplicated");
        assertEquals("${E9X_WORKER_DB_PASSWORD}", String.valueOf(p.getProperty("e9x.jobs.worker-datasource.password")),
                "no default password for the worker login");
    }

    @Test
    void workerFlagExists() throws Exception {
        assertEquals("true", String.valueOf(load().getProperty("app.worker.enabled")));
    }
}
