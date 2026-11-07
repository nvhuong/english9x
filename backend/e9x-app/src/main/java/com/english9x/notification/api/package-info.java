/**
 * Public surface of the {@code notification} module (spec §13): {@link com.english9x.notification.api.NotificationService} to
 * tell a person about an event, {@link com.english9x.notification.api.EventType} for the catalogue, and the channel types. Everything
 * else (storage, templates, jobs, Telegram, HTTP) is internal.
 */
@org.springframework.modulith.NamedInterface("api")
package com.english9x.notification.api;
