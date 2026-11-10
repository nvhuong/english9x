package com.english9x.content.media.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * ffprobe, run as a child process on the stored bytes. The input is forced to the demuxer the file claimed by its magic number
 * ({@code -f}), and only the {@code file} protocol is allowed, so a crafted file cannot make ffprobe open a network address or another
 * file. No shell is involved and the file name is ours (a temp file), not the client's. A probe that overruns its time is killed.
 */
final class FfprobeMediaProbe implements MediaProbe {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_OUTPUT = 1 << 20;

    private final String binary;
    private final Duration timeout;

    FfprobeMediaProbe(String binary, Duration timeout) {
        this.binary = binary;
        this.timeout = timeout;
    }

    private static String demuxer(MediaSniffer.Container c) {
        return switch (c) {
            case MP3 -> "mp3";
            case MP4 -> "mov";
            case WEBM -> "matroska";
            case OGG -> "ogg";
            default -> throw new IllegalArgumentException("not an audio or video container: " + c);
        };
    }

    @Override
    public Result probe(Path file, MediaSniffer.Container container) {
        Process p;
        try {
            p = new ProcessBuilder(binary, "-v", "error", "-protocol_whitelist", "file", "-f", demuxer(container),
                    "-print_format", "json", "-show_entries", "format=duration:stream=codec_type:stream_disposition=attached_pic", file.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            throw new MediaProbeUnavailableException("cannot start ffprobe (" + binary + ")", e);
        }
        try {
            p.getOutputStream().close();
            // Wait first, read after: reading first would block for as long as the process lives and the timeout would never be looked at. The output is a
            // few hundred bytes (-show_entries), far below the pipe buffer; a probe that wrote more than that would block, overrun and be refused as unreadable.
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new MediaUnreadableException("ffprobe did not finish within " + timeout);
            }
            if (p.exitValue() != 0) {
                throw new MediaUnreadableException("ffprobe refused the file (exit " + p.exitValue() + ")");
            }
            byte[] out;
            try (InputStream in = p.getInputStream()) {
                out = in.readNBytes(MAX_OUTPUT);
            }
            return parse(JSON.readTree(out));
        } catch (IOException e) {
            throw new MediaUnreadableException("ffprobe output could not be read");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaUnreadableException("interrupted while probing");
        } finally {
            p.destroyForcibly();
        }
    }

    private static Result parse(JsonNode root) {
        boolean audio = false;
        boolean video = false;
        for (JsonNode s : root.path("streams")) {
            String type = s.path("codec_type").asText("");
            if (type.equals("audio")) {
                audio = true;
            } else if (type.equals("video") && s.path("disposition").path("attached_pic").asInt(0) != 1) {
                video = true;
            }
        }
        String duration = root.path("format").path("duration").asText("");
        long ms;
        try {
            ms = new BigDecimal(duration).movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new MediaUnreadableException("the file has no readable duration");
        }
        if (ms <= 0) {
            throw new MediaUnreadableException("the file has no playable length");
        }
        if (!audio && !video) {
            throw new MediaUnreadableException("the file holds no audio or video stream");
        }
        return new Result(audio, video, ms);
    }
}
