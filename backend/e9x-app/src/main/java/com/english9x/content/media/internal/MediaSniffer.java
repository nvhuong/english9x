package com.english9x.content.media.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The type of a file, read from its first bytes. Never the name, never the Content-Type the client declared. Only the containers the
 * platform allows are recognised (audio mp3 / m4a / webm / ogg, images png / jpg / webp, video mp4 / webm); everything else, including
 * playlists, flash video, WAV, GIF, 3GP, QuickTime, HEIC and Matroska that is not WebM, is {@code Optional.empty()}.
 *
 * <p>This is a gate, not a validator: audio and video are then read in full by ffprobe, which decides the kind (an MP4 may be audio) and the duration.
 */
final class MediaSniffer {

    enum Container {
        MP3("mp3"),
        /** ISO base media: .m4a is audio, .mp4 / .m4v video, and ffprobe says which. */
        MP4("mp4", "m4a", "m4v"),
        WEBM("webm", "weba"),
        OGG("ogg", "oga", "opus"),
        PNG("png"),
        JPEG("jpg", "jpeg"),
        WEBP("webp");

        private final Set<String> extensions;

        Container(String... extensions) {
            this.extensions = Set.of(extensions);
        }

        /** Every extension of every allowed container, sorted: what a file picker may offer. */
        static java.util.List<String> allExtensions() {
            return java.util.Arrays.stream(values()).flatMap(c -> c.extensions.stream()).sorted().toList();
        }

        /** A name with no extension agrees with anything; a name with one must carry an extension of this container ("clip." has an empty one: no). */
        boolean accepts(String filename) {
            int dot = filename.lastIndexOf('.');
            if (dot < 0) {
                return true;
            }
            return extensions.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
        }
    }

    private static final int HEAD = 64;
    private static final Set<String> MP4_BRANDS = Set.of("isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "M4A ", "M4V ", "avc1", "dash", "MSNV");

    private MediaSniffer() {}

    static Optional<Container> sniff(Path file) {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(HEAD);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return sniff(head);
    }

    static Optional<Container> sniff(byte[] b) {
        if (b.length < 4) {
            return Optional.empty();
        }
        if (starts(b, 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a) && b.length >= 16 && ascii(b, 12, 4).equals("IHDR")) {
            return Optional.of(Container.PNG);
        }
        if (starts(b, 0xff, 0xd8, 0xff)) {
            return Optional.of(Container.JPEG);
        }
        if (b.length >= 16 && ascii(b, 0, 4).equals("RIFF") && ascii(b, 8, 4).equals("WEBP") && Set.of("VP8 ", "VP8L", "VP8X").contains(ascii(b, 12, 4))) {
            return Optional.of(Container.WEBP);
        }
        if (ascii(b, 0, 4).equals("OggS")) {
            return Optional.of(Container.OGG);
        }
        if (starts(b, 0x1a, 0x45, 0xdf, 0xa3)) {
            // EBML: WebM and Matroska share it; the DocType element says which
            String head = new String(b, StandardCharsets.ISO_8859_1);
            return head.contains("webm") ? Optional.of(Container.WEBM) : Optional.empty();
        }
        if (b.length >= 12 && ascii(b, 4, 4).equals("ftyp")) {
            return MP4_BRANDS.contains(ascii(b, 8, 4)) ? Optional.of(Container.MP4) : Optional.empty();
        }
        if (isMp3(b)) {
            return Optional.of(Container.MP3);
        }
        return Optional.empty();
    }

    private static boolean isMp3(byte[] b) {
        if (ascii(b, 0, 3).equals("ID3") && b.length >= 10) {
            return (b[3] & 0xff) < 0xff && (b[4] & 0xff) < 0xff;
        }
        // an MPEG audio frame header: 11 bits of sync, a version other than reserved, Layer III, a usable bitrate and sample rate
        int b1 = b[1] & 0xff;
        int b2 = b.length > 2 ? b[2] & 0xff : 0;
        return (b[0] & 0xff) == 0xff && (b1 & 0xe0) == 0xe0
                && ((b1 >> 3) & 3) != 1      // version 01 is reserved
                && ((b1 >> 1) & 3) == 1      // Layer III (ADTS AAC has layer 00)
                && (b2 >> 4) != 0x0f         // bitrate index 1111 is invalid
                && ((b2 >> 2) & 3) != 3;     // sample rate index 11 is reserved
    }

    private static boolean starts(byte[] b, int... prefix) {
        if (b.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xff) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] b, int from, int len) {
        return new String(Arrays.copyOfRange(b, from, Math.min(b.length, from + len)), StandardCharsets.ISO_8859_1);
    }
}
