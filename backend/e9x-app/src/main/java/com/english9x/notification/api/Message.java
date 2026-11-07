package com.english9x.notification.api;

/**
 * A rendered notification, all plain text: {@code title} (one line), {@code body} (data already filled in; values flattened to one
 * line so a value cannot add a header line or break the layout) and {@code link} (absolute, built from the configured public base URL,
 * never from a request). A channel escapes for its own format (HTML for email and Telegram) when it writes the text out; nothing here is
 * markup.
 */
public record Message(EventType type, String title, String body, String link) {}
