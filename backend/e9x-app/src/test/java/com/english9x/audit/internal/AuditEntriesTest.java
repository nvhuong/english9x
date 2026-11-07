package com.english9x.audit.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuditEntriesTest {

    private static final int MAX_BYTES = 8192;

    @Test
    void aWellFormedEntryIsAccepted() {
        assertDoesNotThrow(() -> AuditEntries.checkNames("review.approve-bundle", "review-bundle", UUID.randomUUID().toString()));
        assertEquals("{\"orgId\":\"o\",\"count\":2,\"roles\":[\"AUTHOR\"]}",
                AuditEntries.detailJson(orderedDetail(), MAX_BYTES));
        assertEquals("{}", AuditEntries.detailJson(null, MAX_BYTES));
    }

    private static Map<String, Object> orderedDetail() {
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("orgId", "o");
        detail.put("count", 2);
        detail.put("roles", List.of("AUTHOR"));
        return detail;
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Review.Approve", "review approve", "review..approve", ".review", "1review"})
    void aMalformedActionIsRefused(String action) {
        assertThrows(IllegalArgumentException.class, () -> AuditEntries.checkNames(action, "review", "id"));
    }

    @Test
    void namesAreBounded() {
        assertDoesNotThrow(() -> AuditEntries.checkNames("a".repeat(AuditEntries.MAX_ACTION), "review", "id"));
        assertThrows(IllegalArgumentException.class, () -> AuditEntries.checkNames("a".repeat(AuditEntries.MAX_ACTION + 1), "review", "id"));
        assertThrows(IllegalArgumentException.class, () -> AuditEntries.checkNames("a", "t".repeat(AuditEntries.MAX_TARGET_TYPE + 1), "id"));
        assertThrows(IllegalArgumentException.class, () -> AuditEntries.checkNames("a", "t", ""));
        assertThrows(IllegalArgumentException.class, () -> AuditEntries.checkNames("a", "t", "x".repeat(AuditEntries.MAX_TARGET_ID + 1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"token", "inviteToken", "refresh_token", "password", "PASSWD", "clientSecret", "tokenHash", "authorization", "Cookie", "api-key", "credentials"})
    void aKeyThatMeansASecretIsRefused(String key) {
        assertThrows(IllegalArgumentException.class, () -> AuditEntries.detailJson(Map.of(key, "x"), MAX_BYTES));
    }

    @ParameterizedTest
    @ValueSource(strings = {"comment", "text", "body", "answer", "content", "message", "note", "Comment"})
    void aKeyThatCarriesFreeTextIsRefused(String key) {
        assertThrows(IllegalArgumentException.class, () -> AuditEntries.detailJson(Map.of(key, "x"), MAX_BYTES));
    }

    @Test
    void aForbiddenKeyIsFoundWhenNestedAndARefusalNeverEchoesTheValue() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> AuditEntries.detailJson(Map.of("outer", List.of(Map.of("token", "s3cr3t-value"))), MAX_BYTES));
        assertFalse(refused.getMessage().contains("s3cr3t-value"), refused.getMessage());
    }

    @Test
    void aLongValueIsRefusedWithoutEchoingIt() {
        assertDoesNotThrow(() -> AuditEntries.detailJson(Map.of("name", "x".repeat(AuditEntries.MAX_VALUE_LENGTH)), 2 * AuditEntries.MAX_VALUE_LENGTH));
        String tooLong = "y".repeat(AuditEntries.MAX_VALUE_LENGTH + 1);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> AuditEntries.detailJson(Map.of("name", tooLong), MAX_BYTES));
        assertFalse(refused.getMessage().contains(tooLong));
    }

    @Test
    void theTotalSizeIsBounded() {
        Map<String, Object> detail = Map.of("name", "x".repeat(100));
        int exact = AuditEntries.detailJson(detail, MAX_BYTES).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertDoesNotThrow(() -> AuditEntries.detailJson(detail, exact));
        assertThrows(IllegalArgumentException.class, () -> AuditEntries.detailJson(detail, exact - 1));
    }

    @Test
    void depthAndTypesAreBounded() {
        assertThrows(IllegalArgumentException.class,
                () -> AuditEntries.detailJson(Map.of("a", Map.of("b", Map.of("c", Map.of("d", Map.of("e", 1))))), MAX_BYTES));
        assertThrows(IllegalArgumentException.class, () -> AuditEntries.detailJson(Map.of("a", new Object()), MAX_BYTES));
    }
}
