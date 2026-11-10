package com.english9x.org;

import com.english9x.support.TestAuth;
import com.english9x.support.TestPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Harness of the organisation integration tests: one PostgreSQL database for all subclasses (so they share one application
 * context), fixtures written as the superuser (people, organisations, memberships, grants), and requests made with real
 * signed access tokens and the real {@code X-Org-Context} header. Tests use unique names and emails instead of cleaning up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class OrgIT {

    protected static final String DB = TestPostgres.createDatabase();
    protected static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired
    protected TestRestTemplate http;

    /** Sees past Row-Level Security: fixtures and assertions only. */
    protected final JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));

    protected record Res(int status, JsonNode body, HttpHeaders headers, String raw) {
        public String problemType() {
            return body.path("type").asText();
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------------

    protected static String email(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    protected UUID user(String label) {
        return user(email(label), true);
    }

    protected UUID user(String email, boolean verified) {
        UUID id = UUID.randomUUID();
        su.update("insert into users(id,email,display_name,email_verified_at,consent_at) values (?,?,?,?,now())",
                id, email, "User " + email.substring(0, email.indexOf('@')), verified ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
        return id;
    }

    protected String emailOf(UUID user) {
        return su.queryForObject("select email from users where id = ?", String.class, user);
    }

    protected UUID org(String type) {
        UUID id = UUID.randomUUID();
        su.update("insert into organizations(id,name,type) values (?,?,?)", id, "Org " + id.toString().substring(0, 8), type);
        return id;
    }

    protected void member(UUID org, UUID user) {
        su.update("insert into org_memberships(org_id,user_id) values (?,?) on conflict do nothing", org, user);
    }

    /** A grant; an organisation grant gets its membership first, like every real path. */
    protected void grant(UUID user, UUID org, String exam, String role) {
        if (org != null) {
            member(org, user);
        }
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,?,?)", user, org, exam, role);
    }

    protected UUID sysAdmin() {
        UUID u = user("sys");
        grant(u, null, null, "SYS_ADMIN");
        return u;
    }

    protected UUID orgAdmin(UUID org) {
        UUID u = user("admin");
        grant(u, org, null, "ORG_ADMIN");
        return u;
    }

    protected List<String> grantsOf(UUID user) {
        return su.queryForList("select role || ':' || coalesce(exam_code,'-') || ':' || coalesce(org_id::text,'-') from role_grants where user_id = ? order by 1",
                String.class, user);
    }

    protected boolean isMember(UUID org, UUID user) {
        return Boolean.TRUE.equals(su.queryForObject("select exists (select 1 from org_memberships where org_id = ? and user_id = ?)", Boolean.class, org, user));
    }

    // ---- requests ---------------------------------------------------------------------------------------------------------

    protected Res call(HttpMethod method, String path, Object body, UUID asUser, UUID inOrg) {
        Map<String, String> headers = new HashMap<>();
        if (inOrg != null) {
            headers.put("X-Org-Context", inOrg.toString());
        }
        return call(method, path, body, asUser == null ? null : TestAuth.accessToken(asUser), headers);
    }

    protected Res call(HttpMethod method, String path, Object body, String bearer, Map<String, String> headers) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            h.setBearerAuth(bearer);
        }
        headers.forEach(h::add);
        try {
            String json = body == null ? null : (body instanceof String s ? s : JSON.writeValueAsString(body));
            ResponseEntity<String> r = http.exchange(path, method, new HttpEntity<>(json, h), String.class);
            String raw = r.getBody() == null ? "" : r.getBody();
            String trimmed = raw.trim();
            JsonNode node = trimmed.startsWith("{") || trimmed.startsWith("[") ? JSON.readTree(raw) : JSON.createObjectNode();
            return new Res(r.getStatusCode().value(), node, r.getHeaders(), raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** For tests that make their own request (multipart, say) and want the same shape of answer. */
    protected Res toRes(ResponseEntity<String> r) {
        try {
            String raw = r.getBody() == null ? "" : r.getBody();
            String trimmed = raw.trim();
            return new Res(r.getStatusCode().value(), trimmed.startsWith("{") || trimmed.startsWith("[") ? JSON.readTree(raw) : JSON.createObjectNode(), r.getHeaders(), raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Res get(String path, UUID asUser, UUID inOrg) {
        return call(HttpMethod.GET, path, null, asUser, inOrg);
    }

    /** GET with the query string sent exactly as written: {@link #get} lets the client encode it, so "%00" would arrive as the text "%00". */
    protected Res getAsWritten(String pathAndQuery, UUID asUser) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(TestAuth.accessToken(asUser));
        ResponseEntity<String> r = http.exchange(java.net.URI.create(http.getRootUri() + pathAndQuery), HttpMethod.GET, new HttpEntity<>(null, h), String.class);
        try {
            String raw = r.getBody() == null ? "" : r.getBody();
            return new Res(r.getStatusCode().value(), raw.trim().startsWith("{") ? JSON.readTree(raw) : JSON.createObjectNode(), r.getHeaders(), raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Res post(String path, Object body, UUID asUser, UUID inOrg) {
        return call(HttpMethod.POST, path, body, asUser, inOrg);
    }

    protected static Map<String, Object> role(String role, String exam) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", role);
        m.put("examCode", exam);
        return m;
    }
}
