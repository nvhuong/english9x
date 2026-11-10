package com.english9x.content.media.internal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestMinio;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The images the tests run are the images the stack runs: one pin, written in each place, compared here. */
class MediaInfraFilesTest {

    private static final Path ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static String read(String relative) throws IOException {
        return Files.readString(ROOT.resolve(relative));
    }

    @Test
    void testMinioIsComposeMinio() throws IOException {
        assertTrue(read("infra/docker-compose.yml").contains("image: " + TestMinio.IMAGE), "TestMinio.IMAGE differs from the minio image in infra/docker-compose.yml");
    }

    @Test
    void testFfprobeIsTheImageFfprobe() throws IOException {
        String pin = "mwader/static-ffmpeg:7.1.1@sha256:11a44711684c0b9f754c047dcd64235b8b52deab251bd0e0a86f22faa160749c";
        assertTrue(read("infra/app.Dockerfile").contains(pin), "infra/app.Dockerfile must copy ffprobe from " + pin);
        assertTrue(read("backend/e9x-app/src/test/scripts/ffprobe-docker.sh").contains(pin), "the test wrapper must run " + pin);
    }
}
