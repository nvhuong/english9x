package com.english9x.support;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * One MinIO for the whole test JVM: the image Compose runs (pinned by digest, keep it equal to infra/docker-compose.yml: MediaInfraFilesTest
 * compares them). The application gets the root credentials of this throw-away container; production gives it a key limited to the bucket.
 */
public final class TestMinio {

    public static final String IMAGE = "chainguard/minio@sha256:aea6c163749e1caca37f907976dcf337177ddf5f558808f2aa591618fd3a6eef";
    public static final String ROOT_USER = "e9x-test-minio";
    public static final String ROOT_PASSWORD = "e9x-test-minio-secret";
    public static final String BUCKET = "e9x-media-test";

    private static final GenericContainer<?> CONTAINER = start();

    private TestMinio() {}

    private static GenericContainer<?> start() {
        GenericContainer<?> c = new GenericContainer<>(IMAGE)
                .withCommand("server", "/data")
                .withEnv("MINIO_ROOT_USER", ROOT_USER)
                .withEnv("MINIO_ROOT_PASSWORD", ROOT_PASSWORD)
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).withStartupTimeout(Duration.ofSeconds(90)));
        c.start(); // stopped by Testcontainers' Ryuk when the JVM exits
        return c;
    }

    /** The address the application talks to AND the address the signed URLs are made for: the tests fetch them directly. */
    public static String endpoint() {
        return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(9000);
    }

    public static MinioClient client() {
        return MinioClient.builder().endpoint(endpoint()).credentials(ROOT_USER, ROOT_PASSWORD).build();
    }

    /** Points the application at this MinIO; signed URLs are made for the container's own address so a test can fetch them. */
    public static void configure(DynamicPropertyRegistry registry) {
        registry.add("e9x.media.storage.endpoint", TestMinio::endpoint);
        registry.add("e9x.media.storage.public-url", TestMinio::endpoint);
        registry.add("e9x.media.storage.bucket", () -> BUCKET);
        registry.add("e9x.media.storage.access-key", () -> ROOT_USER);
        registry.add("e9x.media.storage.secret-key", () -> ROOT_PASSWORD);
    }

    public static boolean bucketExists(String bucket) {
        try {
            return client().bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static void makeBucket(String bucket) {
        try {
            client().makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
