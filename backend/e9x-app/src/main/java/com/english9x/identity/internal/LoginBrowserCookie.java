package com.english9x.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.util.WebUtils;

/**
 * Ties a Google hand-over code to the browser that finished the sign-in, which is what stops login CSRF: an attacker who
 * signs in with their own Google account, stops at the redirect and sends the victim the link holds a code, but the
 * victim's browser does not hold this cookie. {@code __Host-} pins it to this exact origin, path {@code /} and HTTPS.
 * {@code Secure} is written unconditionally rather than derived from the request: the application sits behind Caddy on
 * HTTPS and itself sees plain HTTP. It authenticates nothing on its own and is useless without the code.
 */
final class LoginBrowserCookie {

    static final String NAME = "__Host-e9x-login";

    private LoginBrowserCookie() {}

    static void set(HttpServletResponse response, String value, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(value, maxAge).toString());
    }

    static void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    static String read(HttpServletRequest request) {
        jakarta.servlet.http.Cookie c = WebUtils.getCookie(request, NAME);
        return c == null ? null : c.getValue();
    }

    private static ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value).path("/").secure(true).httpOnly(true).sameSite("Strict").maxAge(maxAge).build();
    }
}
