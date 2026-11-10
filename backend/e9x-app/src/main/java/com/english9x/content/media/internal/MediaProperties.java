package com.english9x.content.media.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code e9x.media.*}. The storage block is the object store (MinIO): {@code endpoint} is where the application talks to it (inside the
 * Compose network), {@code publicUrl} the address links are signed for and the browser fetches (the site's own origin, through the proxy).
 * A blank endpoint switches media off: uploads answer 503, links are not made. Nothing here has a usable default for a secret.
 */
@ConfigurationProperties("e9x.media")
record MediaProperties(
        @DefaultValue("10m") Duration urlTtl,
        @DefaultValue Storage storage,
        @DefaultValue("ffprobe") String ffprobePath,
        @DefaultValue("30s") Duration probeTimeout) {

    record Storage(
            @DefaultValue("") String endpoint,
            @DefaultValue("") String publicUrl,
            @DefaultValue("e9x-media") String bucket,
            @DefaultValue("") String accessKey,
            @DefaultValue("") String secretKey,
            @DefaultValue("us-east-1") String region,
            @DefaultValue("true") boolean ensureBucket) {

        boolean configured() {
            return !endpoint.isBlank();
        }
    }
}
