package com.english9x.content.media;

import com.english9x.org.OrgIT;
import com.english9x.support.TestAuth;
import com.english9x.support.TestMinio;
import io.minio.ListObjectsArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Harness of the media tests: a real MinIO (Testcontainers), a real ffprobe (the static build in Docker, or the one on the PATH), and tiny
 * limits (64KB per media file, 128KB per request) so "too large" is cheap to test. Every file in src/test/resources/media is real.
 */
public abstract class MediaIT extends OrgIT {

    protected static final long MEDIA_LIMIT = 64 * 1024;
    protected static final HttpClient RAW = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void media(DynamicPropertyRegistry registry) {
        TestMinio.configure(registry);
        registry.add("e9x.media.ffprobe-path", () -> Path.of("src/test/scripts/ffprobe-docker.sh").toAbsolutePath().toString());
        registry.add("e9x.content.media.max-upload-size", () -> "64KB");
        registry.add("e9x.content.import.max-media-size", () -> "128KB");
    }

    protected static byte[] file(String name) {
        try {
            return Files.readAllBytes(Path.of("src/test/resources/media", name));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An organisation with an AUTHOR of one exam; returns {org, author}. */
    protected UUID[] authorIn(String exam) {
        UUID org = org("CENTER");
        UUID author = user("author");
        grant(author, org, exam, "AUTHOR");
        return new UUID[] {org, author};
    }

    /** The multipart request the CMS sends: the exam in the query, the file as the part "file" under its own name and declared type. */
    protected Res upload(UUID asUser, UUID inOrg, String exam, String filename, byte[] bytes, String declaredType) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (asUser != null) {
            h.setBearerAuth(TestAuth.accessToken(asUser));
        }
        if (inOrg != null) {
            h.add("X-Org-Context", inOrg.toString());
        }
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.parseMediaType(declaredType));
        part.setContentDispositionFormData("file", filename);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, part));
        return toRes(http.exchange("/api/cms/media?examCode=" + exam, HttpMethod.POST, new HttpEntity<>(body, h), String.class));
    }

    protected Res upload(UUID asUser, UUID inOrg, String exam, String name) {
        return upload(asUser, inOrg, exam, name, file(name), declared(name));
    }

    protected static String declared(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1);
        return switch (ext) {
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "ogg" -> "audio/ogg";
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "mp4" -> "video/mp4";
            default -> "application/octet-stream";
        };
    }

    /** What is stored in MinIO now, by object key. */
    protected static Map<String, Long> storedObjects() {
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

    protected static HttpResponse<byte[]> fetch(String url) {
        try {
            return RAW.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected int mediaRows() {
        return su.queryForObject("select count(*) from media_assets", Integer.class);
    }
}
