package com.english9x.content.importer;

import com.english9x.support.TestAuth;
import com.english9x.support.TestMinio;
import com.english9x.support.TestPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.ListObjectsArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
 * Harness of the import tests. Its OWN database, not the one the other organisation tests share: the background worker of every cached Spring context of
 * the test JVM polls the {@code jobs} table of its database, and a context that has no MinIO (most of them) would claim an {@code excel_import} job it cannot
 * serve. A real MinIO, a real ffprobe (the static build in Docker, or the one on the PATH), tiny media limits, and fixtures written as the superuser.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class ImportIT {

    static final String DB = TestPostgres.createDatabase();
    static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
        TestMinio.configure(registry);
        registry.add("e9x.media.ffprobe-path", () -> Path.of("src/test/scripts/ffprobe-docker.sh").toAbsolutePath().toString());
        registry.add("e9x.content.media.max-upload-size", () -> "64KB");
        registry.add("e9x.content.import.max-media-size", () -> "128KB");
    }

    @Autowired
    TestRestTemplate http;

    /** Sees past Row-Level Security: fixtures and assertions only. */
    final JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));

    record Res(int status, JsonNode body, HttpHeaders headers, String raw) {
        String problemType() {
            return body.path("type").asText();
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------------------------

    UUID user(String label) {
        UUID id = UUID.randomUUID();
        String email = label + "-" + id.toString().substring(0, 8) + "@example.test";
        su.update("insert into users(id,email,display_name,email_verified_at,consent_at) values (?,?,?,now(),now())", id, email, "User " + label);
        return id;
    }

    UUID org(String type) {
        UUID id = UUID.randomUUID();
        su.update("insert into organizations(id,name,type) values (?,?,?)", id, "Org " + id.toString().substring(0, 8), type);
        return id;
    }

    void grant(UUID user, UUID org, String exam, String role) {
        if (org != null) {
            su.update("insert into org_memberships(org_id,user_id) values (?,?) on conflict do nothing", org, user);
        }
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,?,?)", user, org, exam, role);
    }

    /** An organisation with an AUTHOR of one exam; returns {org, author}. */
    UUID[] authorIn(String exam) {
        UUID org = org("CENTER");
        UUID author = user("author");
        grant(author, org, exam, "AUTHOR");
        return new UUID[] {org, author};
    }

    static byte[] file(String name) {
        try {
            return Files.readAllBytes(Path.of("src/test/resources/media", name));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    int mediaRows() {
        return su.queryForObject("select count(*) from media_assets", Integer.class);
    }

    /** What is stored in MinIO now, by object key. */
    static Map<String, Long> storedObjects() {
        Map<String, Long> out = new HashMap<>();
        try {
            for (Result<Item> r : TestMinio.client().listObjects(ListObjectsArgs.builder().bucket(TestMinio.BUCKET).recursive(true).build())) {
                Item i = r.get();
                out.put(i.objectName(), i.size());
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // ---- requests ---------------------------------------------------------------------------------------------------------------------

    Res call(HttpMethod method, String path, Object body, UUID asUser, UUID inOrg) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(TestAuth.accessToken(asUser));
        if (inOrg != null) {
            h.add("X-Org-Context", inOrg.toString());
        }
        try {
            String json = body == null ? null : (body instanceof String s ? s : JSON.writeValueAsString(body));
            return toRes(http.exchange(path, method, new HttpEntity<>(json, h), String.class));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    Res get(String path, UUID asUser, UUID inOrg) {
        return call(HttpMethod.GET, path, null, asUser, inOrg);
    }

    Res post(String path, Object body, UUID asUser, UUID inOrg) {
        return call(HttpMethod.POST, path, body, asUser, inOrg);
    }

    Res toRes(ResponseEntity<String> r) {
        try {
            String raw = r.getBody() == null ? "" : r.getBody();
            String trimmed = raw.trim();
            return new Res(r.getStatusCode().value(), trimmed.startsWith("{") || trimmed.startsWith("[") ? JSON.readTree(raw) : JSON.createObjectNode(), r.getHeaders(), raw);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
