package com.english9x.content.media.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestMinio;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The object store: the bucket exists after start-up, ensuring it twice is harmless, a missing object is an error, not a silent success. */
class MediaStorageIT {

    private static MinioObjectStore store(String bucket) {
        return new MinioObjectStore(new MediaProperties.Storage(TestMinio.endpoint(), TestMinio.endpoint(), bucket, TestMinio.ROOT_USER,
                TestMinio.ROOT_PASSWORD, "us-east-1", true));
    }

    @Test
    void ensureBucketCreatesItOnceAndIsIdempotent() {
        String bucket = "e9x-ensure-" + System.nanoTime();
        assertFalse(TestMinio.bucketExists(bucket));
        MinioObjectStore s = store(bucket);
        s.ensureBucket();
        assertTrue(TestMinio.bucketExists(bucket));
        s.ensureBucket();
        store(bucket).ensureBucket();
        assertTrue(TestMinio.bucketExists(bucket));
    }

    @Test
    void putThenDeleteThenAMissingObjectIsNotFound() throws Exception {
        String bucket = "e9x-put-" + System.nanoTime();
        MinioObjectStore s = store(bucket);
        s.ensureBucket();
        Path f = Files.createTempFile("e9x-store", ".bin");
        try {
            Files.write(f, new byte[] {1, 2, 3});
            s.put("k1", f, "image/png");
            assertTrue(s.exists("k1"));
            s.delete("k1");
            assertFalse(s.exists("k1"));
            s.delete("k1"); // deleting what is not there is not an error (the compensation after a failed insert may run twice)
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void anUnreachableStoreIsAStorageProblemNotAnUnexpectedError() {
        MinioObjectStore s = new MinioObjectStore(new MediaProperties.Storage("http://127.0.0.1:1", "http://127.0.0.1:1", "x", "a", "b", "us-east-1", true));
        assertThrows(MediaStorageException.class, s::ensureBucket);
    }
}
