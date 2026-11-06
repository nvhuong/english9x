package com.english9x.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** Without Google credentials the feature is simply off: the app starts, the route answers 404, the SPA hides the button. */
class GoogleDisabledIT extends IdentityIT {

    @Test
    void routeIs404ProblemAndConfigSaysDisabled() {
        Res start = get("/api/auth/oauth2/google", null);
        assertEquals(404, start.status(), start.raw());
        assertEquals("urn:e9x:problem:google-login-disabled", start.problemType());
        assertEquals(404, get("/api/auth/oauth2/callback/google?code=x&state=y", null).status());
        assertFalse(get("/api/auth/config", null).body().get("googleEnabled").asBoolean());
    }
}
