package com.english9x.content.media.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MediaProperties.class, UploadLimits.class})
class MediaConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(MediaConfiguration.class);

    @Bean
    ObjectStore objectStore(MediaProperties props) {
        MediaProperties.Storage s = props.storage();
        if (!s.configured()) {
            LOG.warn("e9x.media.storage.endpoint is blank: media uploads and links are switched off");
            return new UnavailableObjectStore();
        }
        if (s.accessKey().isBlank() || s.secretKey().isBlank()) {
            throw new IllegalStateException("e9x.media.storage.access-key and secret-key (E9X_MINIO_ACCESS_KEY, E9X_MINIO_SECRET_KEY) are required when the media endpoint is set");
        }
        if (s.publicUrl().isBlank()) {
            throw new IllegalStateException("e9x.media.storage.public-url (default e9x.public-base-url, E9X_PUBLIC_BASE_URL) is required when the media endpoint is set: "
                    + "links are signed for the address the browser uses, never for the internal one");
        }
        return new MinioObjectStore(s);
    }

    @Bean
    MediaProbe mediaProbe(MediaProperties props) {
        return new FfprobeMediaProbe(props.ffprobePath(), props.probeTimeout());
    }

    /** The bucket exists before the first request (spec 15: a private bucket). Idempotent; a store that cannot be reached stops the start-up, loudly. */
    @Bean
    ApplicationRunner ensureMediaBucket(ObjectStore store, MediaProperties props) {
        return args -> {
            if (props.storage().configured() && props.storage().ensureBucket()) {
                store.ensureBucket();
            }
        };
    }
}
