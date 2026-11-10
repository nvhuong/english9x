package com.english9x.content.media.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Duration and streams come from ffprobe reading the stored bytes (the static build in Docker, or ffprobe on the PATH). */
class FfprobeMediaProbeIT {

    @TempDir
    Path dir;

    private final FfprobeMediaProbe probe = new FfprobeMediaProbe(Path.of("src/test/scripts/ffprobe-docker.sh").toAbsolutePath().toString(), Duration.ofSeconds(60));

    private static Path media(String name) {
        return Path.of("src/test/resources/media", name);
    }

    @Test
    void readsDurationAndStreamsOfEveryContainer() {
        MediaProbe.Result mp3 = probe.probe(media("tone.mp3"), MediaSniffer.Container.MP3);
        assertTrue(mp3.audio() && !mp3.video() && mp3.durationMs() >= 1400 && mp3.durationMs() <= 1700, mp3.toString());
        MediaProbe.Result m4a = probe.probe(media("tone.m4a"), MediaSniffer.Container.MP4);
        assertTrue(m4a.audio() && !m4a.video(), m4a.toString());
        assertEquals(2000, m4a.durationMs(), 60);
        MediaProbe.Result mp4 = probe.probe(media("clip.mp4"), MediaSniffer.Container.MP4);
        assertTrue(mp4.video() && mp4.audio(), mp4.toString());
        MediaProbe.Result webmVideo = probe.probe(media("clip.webm"), MediaSniffer.Container.WEBM);
        assertTrue(webmVideo.video(), webmVideo.toString());
        MediaProbe.Result webmAudio = probe.probe(media("tone.webm"), MediaSniffer.Container.WEBM);
        assertTrue(webmAudio.audio() && !webmAudio.video(), webmAudio.toString());
        MediaProbe.Result ogg = probe.probe(media("tone.ogg"), MediaSniffer.Container.OGG);
        assertTrue(ogg.audio() && !ogg.video(), ogg.toString());
        assertEquals(1200, ogg.durationMs(), 60);
    }

    @Test
    void aFileFfprobeCannotUseIsAnErrorNotAGuess() throws Exception {
        Path hollow = dir.resolve("hollow.mp3");
        byte[] b = new byte[2048];
        b[0] = 'I'; b[1] = 'D'; b[2] = '3'; b[3] = 4;
        Files.write(hollow, b);
        assertThrows(MediaUnreadableException.class, () -> probe.probe(hollow, MediaSniffer.Container.MP3));
        Path cut = dir.resolve("cut.mp4");
        byte[] mp4 = Files.readAllBytes(media("clip.mp4"));
        Files.write(cut, java.util.Arrays.copyOf(mp4, 60)); // the ftyp box and the start of the next one: no movie in it
        assertThrows(MediaUnreadableException.class, () -> probe.probe(cut, MediaSniffer.Container.MP4));
    }

    @Test
    void aMissingOrBrokenProbeBinaryIsAStorageSideFailureNotAnUnreadableFile() {
        FfprobeMediaProbe missing = new FfprobeMediaProbe("/nonexistent/ffprobe", Duration.ofSeconds(5));
        assertThrows(MediaProbeUnavailableException.class, () -> missing.probe(media("tone.mp3"), MediaSniffer.Container.MP3));
    }

    @Test
    void aProbeThatRunsTooLongIsStopped() throws Exception {
        Path slow = dir.resolve("slow-ffprobe.sh");
        Files.writeString(slow, "#!/bin/sh\nsleep 30\n");
        slow.toFile().setExecutable(true);
        long start = System.nanoTime();
        assertThrows(MediaUnreadableException.class, () -> new FfprobeMediaProbe(slow.toString(), Duration.ofMillis(500)).probe(media("tone.mp3"), MediaSniffer.Container.MP3));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10, "gave up after the timeout, did not wait for the process");
    }
}
