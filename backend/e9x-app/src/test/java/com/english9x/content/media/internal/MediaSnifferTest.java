package com.english9x.content.media.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The container of a file is read from its first bytes, never from its name. */
class MediaSnifferTest {

    @TempDir
    Path dir;

    private Optional<MediaSniffer.Container> sniff(byte[] bytes) throws IOException {
        Path f = dir.resolve("f-" + System.nanoTime());
        Files.write(f, bytes);
        return MediaSniffer.sniff(f);
    }

    private static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/media", name));
    }

    @Test
    void everyAllowedContainerIsRecognisedByItsBytes() throws IOException {
        assertEquals(Optional.of(MediaSniffer.Container.MP3), sniff(fixture("tone.mp3")));
        assertEquals(Optional.of(MediaSniffer.Container.MP4), sniff(fixture("tone.m4a")));
        assertEquals(Optional.of(MediaSniffer.Container.MP4), sniff(fixture("clip.mp4")));
        assertEquals(Optional.of(MediaSniffer.Container.WEBM), sniff(fixture("tone.webm")));
        assertEquals(Optional.of(MediaSniffer.Container.WEBM), sniff(fixture("clip.webm")));
        assertEquals(Optional.of(MediaSniffer.Container.OGG), sniff(fixture("tone.ogg")));
        assertEquals(Optional.of(MediaSniffer.Container.PNG), sniff(fixture("pixel.png")));
        assertEquals(Optional.of(MediaSniffer.Container.JPEG), sniff(fixture("pixel.jpg")));
        assertEquals(Optional.of(MediaSniffer.Container.WEBP), sniff(fixture("pixel.webp")));
    }

    @Test
    void anMp3WithoutAnId3TagIsFoundByItsFrameHeader() throws IOException {
        byte[] bare = fixture("tone.mp3");
        int id3Size = ((bare[6] & 0x7f) << 21) | ((bare[7] & 0x7f) << 14) | ((bare[8] & 0x7f) << 7) | (bare[9] & 0x7f);
        byte[] frames = java.util.Arrays.copyOfRange(bare, 10 + id3Size, bare.length);
        assertEquals(Optional.of(MediaSniffer.Container.MP3), sniff(frames));
    }

    @Test
    void whatIsNotAllowedIsNotRecognised() throws IOException {
        assertEquals(Optional.empty(), sniff(new byte[0]));
        assertEquals(Optional.empty(), sniff(new byte[] {0x1a}));
        assertEquals(Optional.empty(), sniff("hello, this is plain text".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Optional.empty(), sniff("#EXTM3U\n#EXT-X-VERSION:3\nhttp://169.254.169.254/\n".getBytes(StandardCharsets.UTF_8)), "a playlist is a way to make ffprobe fetch URLs");
        assertEquals(Optional.empty(), sniff(new byte[] {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0}), "an executable");
        assertEquals(Optional.empty(), sniff(new byte[] {'F', 'L', 'V', 1, 5, 0, 0, 0, 9}), "flv");
        assertEquals(Optional.empty(), sniff("GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1)), "gif");
        assertEquals(Optional.empty(), sniff("RIFF$\u0000\u0000\u0000WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1)), "wav is a RIFF but not a WEBP");
        assertEquals(Optional.empty(), sniff(new byte[] {(byte) 0xff, (byte) 0xf1, 0x50, (byte) 0x80, 0, 0x1f, (byte) 0xfc}), "ADTS AAC is not an mp3 frame");
        // an ISO media file of another brand: 3GP, QuickTime, HEIC
        for (String brand : new String[] {"3gp4", "qt  ", "heic", "avif"}) {
            byte[] b = new byte[16];
            b[3] = 0x10;
            System.arraycopy("ftyp".getBytes(StandardCharsets.ISO_8859_1), 0, b, 4, 4);
            System.arraycopy(brand.getBytes(StandardCharsets.ISO_8859_1), 0, b, 8, 4);
            assertEquals(Optional.empty(), sniff(b), brand);
        }
        // Matroska that is not WebM
        byte[] mkv = new byte[64];
        System.arraycopy(new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, (byte) 0x9f, 0x42, (byte) 0x82, (byte) 0x88}, 0, mkv, 0, 8);
        System.arraycopy("matroska".getBytes(StandardCharsets.ISO_8859_1), 0, mkv, 8, 8);
        assertEquals(Optional.empty(), sniff(mkv));
    }

    @Test
    void theNameMustAgreeWithTheBytesWhenItHasAnExtension() {
        assertTrue(MediaSniffer.Container.MP3.accepts("lesson.mp3"));
        assertTrue(MediaSniffer.Container.MP3.accepts("LESSON.MP3"));
        assertTrue(MediaSniffer.Container.MP3.accepts("no-extension"));
        assertTrue(MediaSniffer.Container.MP3.accepts("a.b.c.mp3"));
        assertFalse(MediaSniffer.Container.MP3.accepts("lesson.png"));
        assertFalse(MediaSniffer.Container.MP3.accepts("lesson.exe"));
        assertFalse(MediaSniffer.Container.PNG.accepts("lesson.mp3"));
        assertTrue(MediaSniffer.Container.MP4.accepts("a.m4a") && MediaSniffer.Container.MP4.accepts("a.mp4"));
        assertTrue(MediaSniffer.Container.JPEG.accepts("a.jpg") && MediaSniffer.Container.JPEG.accepts("a.jpeg"));
        assertFalse(MediaSniffer.Container.WEBM.accepts("a.mp4"));
        assertFalse(MediaSniffer.Container.MP3.accepts("trailing-dot."), "an empty extension is not 'no extension'");
    }
}
