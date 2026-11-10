package com.english9x.content.media.internal;

import java.nio.file.Path;

/** Reads what is in a stored audio or video file: which streams it holds and how long it plays. */
interface MediaProbe {

    /**
     * @param audio holds an audio stream
     * @param video holds a real video stream (a cover picture attached to an mp3 does not count)
     * @param durationMs rounded to the millisecond, always positive
     * @throws MediaUnreadableException the file is not what it claims, or no usable duration can be read
     * @throws MediaProbeUnavailableException the tool is missing or broken
     */
    Result probe(Path file, MediaSniffer.Container container);

    record Result(boolean audio, boolean video, long durationMs) {}
}
