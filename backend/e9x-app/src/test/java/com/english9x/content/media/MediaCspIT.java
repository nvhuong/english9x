package com.english9x.content.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.OrgIT;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The media of the CMS and of the exam room are played and shown from the same origin (the signed link goes through Caddy's own host),
 * so the policy allows 'self' for media and images and nothing else: no wildcard, no scheme, no host. blob: and data: were already there
 * (recordings, inline icons). A later widening shows up here.
 */
class MediaCspIT extends OrgIT {

    private Map<String, List<String>> directives() {
        String csp = get("/api/platform/ping", null, null).headers().getFirst("Content-Security-Policy");
        Map<String, List<String>> out = new HashMap<>();
        for (String d : csp.split(";")) {
            String[] parts = d.trim().split("\\s+");
            out.put(parts[0], Arrays.asList(parts).subList(1, parts.length));
        }
        return out;
    }

    @Test
    void mediaAndImagesAreSameOriginOnly() {
        Map<String, List<String>> csp = directives();
        assertEquals(List.of("'self'", "blob:"), csp.get("media-src"));
        assertEquals(List.of("'self'", "data:", "blob:"), csp.get("img-src"));
        assertEquals(List.of("'self'"), csp.get("default-src"));
        assertEquals(List.of("'self'"), csp.get("connect-src"), "the upload and the signed fetch go to the page's own origin");
        for (String k : List.of("media-src", "img-src", "default-src", "connect-src", "script-src")) {
            for (String v : csp.get(k)) {
                assertFalse(v.equals("*") || v.startsWith("http:") || v.startsWith("https:") || v.contains("."), k + " must not allow another origin: " + v);
            }
        }
        assertFalse(csp.get("script-src").contains("'unsafe-inline'") || csp.get("script-src").contains("'unsafe-eval'"));
        assertTrue(csp.containsKey("frame-ancestors") && csp.containsKey("object-src"));
    }
}
