package com.english9x.audit.api;

import com.english9x.audit.internal.AuditEntries;
import java.util.Map;

/**
 * The rules every audit record obeys (shape of names, content of {@code detail}: no secret-like key, no free text, bounded size), for the one
 * writer that is not {@code audit} itself: the SYSTEM record of a background job, which {@code platform.jobs} writes through the worker connection
 * (the audit module may not depend on the platform module, which depends on it).
 */
public final class AuditRules {

    /** The size ceiling of a SYSTEM record's detail; a person's records use {@code e9x.audit.max-detail-bytes}. */
    public static final int SYSTEM_MAX_DETAIL_BYTES = 4096;

    private AuditRules() {}

    /** @throws IllegalArgumentException when a name has the wrong shape */
    public static void checkNames(String action, String targetType, String targetId) {
        AuditEntries.checkNames(action, targetType, targetId);
    }

    /** @return the detail as JSON text @throws IllegalArgumentException when it holds a forbidden key, a long text or too much */
    public static String detailJson(Map<String, Object> detail) {
        return AuditEntries.detailJson(detail, SYSTEM_MAX_DETAIL_BYTES);
    }
}
