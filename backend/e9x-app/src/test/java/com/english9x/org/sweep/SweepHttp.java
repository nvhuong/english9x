package com.english9x.org.sweep;

import com.english9x.support.TestAuth;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

/**
 * How the sweeps make a request: a real signed token and the real X-Org-Context header, as the sweep's caller (org A's ORG_ADMIN), as the system
 * administrator, or as org A's author. A body written {@code MULTIPART:<exam>:<file in src/test/resources/media>} is sent as the CMS upload.
 */
public final class SweepHttp {

    private SweepHttp() {}

    public static int status(TestRestTemplate http, SweepFixtures f, ForeignIdSweep.Call c) {
        HttpHeaders h = new HttpHeaders();
        if (ForeignIdSweep.ADMIN.equals(c.caller())) {
            h.setBearerAuth(TestAuth.accessToken(f.admin));
        } else {
            h.add("X-Org-Context", f.orgA.toString());
            h.setBearerAuth(TestAuth.accessToken(ForeignIdSweep.AUTHOR.equals(c.caller()) ? f.authorA : f.userA));
        }
        if (c.body() != null && c.body().startsWith("MULTIPART:")) {
            String[] parts = c.body().split(":");
            h.setContentType(MediaType.MULTIPART_FORM_DATA);
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(Path.of("src/test/resources/media", parts[2]));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return parts[2];
                }
            });
            return http.exchange(c.path() + "?examCode=" + parts[1], HttpMethod.valueOf(c.method()), new HttpEntity<>(body, h), String.class).getStatusCode().value();
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(c.path(), HttpMethod.valueOf(c.method()), new HttpEntity<>(c.body(), h), String.class).getStatusCode().value();
    }
}
