package com.english9x.content.media.internal;

import io.minio.BucketExistsArgs;
import io.minio.DownloadObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MinIO over S3. Two clients: {@code internal} talks to the store (Compose network), {@code signing} is built for the PUBLIC address, so a
 * link carries the host the browser will send and the proxy preserves (SigV4 signs the Host header). The signing client never connects: with
 * the region given, {@code getPresignedObjectUrl} is pure arithmetic over the credentials.
 */
final class MinioObjectStore implements ObjectStore {

    private static final Logger LOG = LoggerFactory.getLogger(MinioObjectStore.class);
    private static final Duration MAX_LINK = Duration.ofDays(7); // S3's own ceiling

    private final MinioClient internal;
    private final MinioClient signing;
    private final String bucket;

    MinioObjectStore(MediaProperties.Storage s) {
        this.bucket = s.bucket();
        this.internal = client(s.endpoint(), s);
        this.internal.setTimeout(TimeUnit.SECONDS.toMillis(10), TimeUnit.MINUTES.toMillis(10), TimeUnit.MINUTES.toMillis(10));
        this.signing = client(s.publicUrl(), s);
    }

    private static MinioClient client(String endpoint, MediaProperties.Storage s) {
        return MinioClient.builder()
                .endpoint(endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint)
                .credentials(s.accessKey(), s.secretKey())
                .region(s.region())
                .build();
    }

    @Override
    public void ensureBucket() {
        try {
            if (!internal.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                try {
                    internal.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    LOG.info("media bucket {} created", bucket);
                } catch (ErrorResponseException e) {
                    // two instances starting together: the loser finds it already there
                    String code = e.errorResponse().code();
                    if (!"BucketAlreadyOwnedByYou".equals(code) && !"BucketAlreadyExists".equals(code)) {
                        throw e;
                    }
                }
            }
        } catch (Exception e) {
            throw new MediaStorageException("cannot ensure the media bucket '" + bucket + "'", e);
        }
    }

    @Override
    public void put(String key, Path file, String contentType) {
        try {
            internal.uploadObject(UploadObjectArgs.builder().bucket(bucket).object(key).filename(file.toString()).contentType(contentType).build());
        } catch (Exception e) {
            throw new MediaStorageException("cannot store the media object", e);
        }
    }

    @Override
    public void get(String key, Path target) {
        try {
            java.nio.file.Files.deleteIfExists(target);
            internal.downloadObject(DownloadObjectArgs.builder().bucket(bucket).object(key).filename(target.toString()).build());
        } catch (Exception e) {
            throw new MediaStorageException("cannot read the stored object", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            internal.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new MediaStorageException("cannot delete the media object", e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            internal.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            String code = e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NoSuchBucket".equals(code)) {
                return false;
            }
            throw new MediaStorageException("cannot look for the media object", e);
        } catch (Exception e) {
            throw new MediaStorageException("cannot look for the media object", e);
        }
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        if (ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(MAX_LINK) > 0) {
            throw new IllegalArgumentException("a link lives between 1 second and 7 days, not " + ttl);
        }
        try {
            return URI.create(signing.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(bucket).object(key).expiry((int) ttl.toSeconds()).build()));
        } catch (Exception e) {
            throw new MediaStorageException("cannot sign a media link", e);
        }
    }
}
