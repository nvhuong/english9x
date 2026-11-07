package com.english9x.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** An in-JVM OpenID Connect provider for the Google sign-in tests: authorize, token (PKCE checked) and JWKS. */
public final class FakeOidcProvider implements AutoCloseable {

    public static final String CLIENT_ID = "test-client-id";
    public static final String CLIENT_SECRET = "test-client-secret";

    /** What the provider will assert about the person who signs in next, plus ways to misbehave. */
    public static final class Scenario {
        public String sub = "google-sub-" + UUID.randomUUID();
        public String email;
        public boolean emailVerified = true;
        public String name = "Google Person";
        public String locale = "en";
        public String nonceOverride;      // wrong nonce in the ID token
        public String audience = CLIENT_ID;
        public String issuerOverride;
        public String stateOverride;      // wrong state on the redirect back
    }

    private record Issued(Scenario scenario, String nonce, String challenge) {}

    private final HttpServer server;
    private final RSAKey key;
    private final Map<String, Issued> codes = new ConcurrentHashMap<>();
    private volatile Scenario next = new Scenario();

    public FakeOidcProvider() {
        try {
            key = new RSAKeyGenerator(2048).keyID("fake-key-1").generate();
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/authorize", this::authorize);
        server.createContext("/token", this::token);
        server.createContext("/jwks", this::jwks);
        server.start();
    }

    public String base() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public String issuer() {
        return base();
    }

    public void willSignIn(Scenario scenario) {
        this.next = scenario;
    }

    private void authorize(HttpExchange ex) throws IOException {
        Map<String, String> q = params(ex.getRequestURI().getRawQuery());
        Scenario s = next;
        String code = UUID.randomUUID().toString();
        codes.put(code, new Issued(s, q.get("nonce"), q.get("code_challenge")));
        String state = s.stateOverride != null ? s.stateOverride : q.get("state");
        String back = q.get("redirect_uri") + (q.get("redirect_uri").contains("?") ? "&" : "?")
                + "code=" + enc(code) + "&state=" + enc(state);
        ex.getResponseHeaders().add("Location", back);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private void token(HttpExchange ex) throws IOException {
        try {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String expected = "Basic " + Base64.getEncoder().encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
            Map<String, String> form = params(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Issued issued = codes.remove(form.get("code"));
            if (!expected.equals(auth) || issued == null || !pkceOk(form.get("code_verifier"), issued.challenge())) {
                reply(ex, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            Scenario s = issued.scenario();
            Instant now = Instant.now();
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(s.issuerOverride != null ? s.issuerOverride : issuer())
                    .subject(s.sub).audience(s.audience)
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                    .claim("nonce", s.nonceOverride != null ? s.nonceOverride : issued.nonce())
                    .claim("name", s.name).claim("locale", s.locale);
            if (s.email != null) {
                claims.claim("email", s.email).claim("email_verified", s.emailVerified);
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            Map<String, Object> body = new HashMap<>();
            body.put("access_token", "fake-access-token");
            body.put("token_type", "Bearer");
            body.put("expires_in", 300);
            body.put("id_token", jwt.serialize());
            reply(ex, 200, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body));
        } catch (Exception e) {
            reply(ex, 500, "{\"error\":\"server_error\"}");
        }
    }

    private void jwks(HttpExchange ex) throws IOException {
        reply(ex, 200, new JWKSet(key.toPublicJWK()).toString());
    }

    private static boolean pkceOk(String verifier, String challenge) throws Exception {
        if (verifier == null || challenge == null) {
            return false;
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).equals(challenge);
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static Map<String, String> params(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                out.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
