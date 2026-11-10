package com.english9x.content.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** POST /api/cms/media (F12): the type comes from the bytes, the duration from ffprobe, the limit from the configuration key. */
class MediaUploadIT extends MediaIT {

    @Test
    void readsAudioDuration() {
        UUID[] a = authorIn("IELTS");
        Res r = upload(a[1], a[0], "IELTS", "tone.mp3");
        assertEquals(200, r.status(), r.raw());
        assertEquals("AUDIO", r.body().path("kind").asText());
        assertEquals("audio/mpeg", r.body().path("mimeType").asText());
        long ms = r.body().path("durationMs").asLong();
        assertTrue(ms >= 1400 && ms <= 1700, "a 1.5 s tone reads as about 1500 ms, not as the client's claim: " + ms);
        UUID id = UUID.fromString(r.body().path("id").asText());
        assertEquals(ms, su.queryForObject("select duration_ms from media_assets where id = ?", Long.class, id));
        assertEquals(a[0], su.queryForObject("select owner_org_id from media_assets where id = ?", UUID.class, id));
        assertEquals("IELTS", su.queryForObject("select exam_code from media_assets where id = ?", String.class, id));
        assertEquals(a[1], su.queryForObject("select uploaded_by from media_assets where id = ?", UUID.class, id));
        assertEquals((long) file("tone.mp3").length, storedObjects().get(id.toString()), "the object is stored under the row's id, not under the client's name");
    }

    @Test
    void everyAllowedTypeIsAcceptedByItsBytes() {
        UUID[] a = authorIn("IELTS");
        Map<String, List<String>> expected = Map.of(
                "tone.mp3", List.of("AUDIO", "audio/mpeg"), "tone.m4a", List.of("AUDIO", "audio/mp4"),
                "tone.webm", List.of("AUDIO", "audio/webm"), "tone.ogg", List.of("AUDIO", "audio/ogg"),
                "pixel.png", List.of("IMAGE", "image/png"), "pixel.jpg", List.of("IMAGE", "image/jpeg"), "pixel.webp", List.of("IMAGE", "image/webp"),
                "clip.mp4", List.of("VIDEO", "video/mp4"), "clip.webm", List.of("VIDEO", "video/webm"));
        for (var e : expected.entrySet()) {
            Res r = upload(a[1], a[0], "IELTS", e.getKey());
            assertEquals(200, r.status(), e.getKey() + " " + r.raw());
            assertEquals(e.getValue().get(0), r.body().path("kind").asText(), e.getKey());
            assertEquals(e.getValue().get(1), r.body().path("mimeType").asText(), e.getKey());
            boolean image = "IMAGE".equals(e.getValue().get(0));
            assertEquals(image, r.body().path("durationMs").isNull() || r.body().path("durationMs").isMissingNode(), e.getKey() + " duration: " + r.raw());
            if (!image) {
                assertTrue(r.body().path("durationMs").asLong() >= 900, e.getKey() + " " + r.raw());
            }
        }
    }

    @Test
    void rejectsSpoofedExtension() {
        UUID[] a = authorIn("IELTS");
        int rows = mediaRows();
        Map<String, Long> before = storedObjects();
        // an executable, a script and plain text dressed as audio: the name and the declared type say mp3, the bytes say otherwise
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xff, (byte) 0xff, 0, 0};
        for (byte[] fake : List.of(exe, "#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8), "<?php echo 1;".getBytes(StandardCharsets.UTF_8),
                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8))) {
            Res r = upload(a[1], a[0], "IELTS", "lesson.mp3", fake, "audio/mpeg");
            assertEquals(415, r.status(), r.raw());
            assertEquals("urn:e9x:problem:media-type-not-allowed", r.problemType());
        }
        // real media under the wrong name: a PNG that says it is an mp3, an mp3 that says it is a png, a webm that says it is an mp4
        Res png = upload(a[1], a[0], "IELTS", "answer.mp3", file("pixel.png"), "audio/mpeg");
        assertEquals(415, png.status(), png.raw());
        assertEquals("urn:e9x:problem:media-extension-mismatch", png.problemType());
        assertEquals(415, upload(a[1], a[0], "IELTS", "photo.png", file("tone.mp3"), "image/png").status());
        assertEquals(415, upload(a[1], a[0], "IELTS", "clip.mp4", file("clip.webm"), "video/mp4").status());
        // the declared type is never believed either way: a right name and a right declared type over the wrong bytes
        assertEquals(415, upload(a[1], a[0], "IELTS", "tone.mp3", file("pixel.jpg"), "audio/mpeg").status());
        // and a type that is real media but not allowed here
        assertEquals(415, upload(a[1], a[0], "IELTS", "movie.flv", new byte[] {'F', 'L', 'V', 1, 5, 0, 0, 0, 9, 0, 0, 0, 0}, "video/x-flv").status());
        assertEquals(rows, mediaRows(), "nothing was recorded");
        assertEquals(before, storedObjects(), "and nothing was stored");
    }

    @Test
    void theNameDecidesNothingWhenItIsRight() {
        UUID[] a = authorIn("IELTS");
        // the true type under an unusual but matching name, and a name with no extension at all
        assertEquals(200, upload(a[1], a[0], "IELTS", "Listening Part 1 (final).MP3", file("tone.mp3"), "application/octet-stream").status());
        assertEquals(200, upload(a[1], a[0], "IELTS", "recording", file("tone.webm"), "application/octet-stream").status());
        Res path = upload(a[1], a[0], "IELTS", "../../etc/passwd.mp3", file("tone.mp3"), "audio/mpeg");
        assertEquals(200, path.status(), path.raw());
        assertEquals("passwd.mp3", su.queryForObject("select original_filename from media_assets where id = ?", String.class, UUID.fromString(path.body().path("id").asText())),
                "only the base name is kept");
    }

    @Test
    void refusesWhatFfprobeCannotRead() {
        UUID[] a = authorIn("IELTS");
        int rows = mediaRows();
        Map<String, Long> before = storedObjects();
        // right magic number, no audio behind it: the type check passes, the probe does not, and nothing is stored with a made-up duration
        byte[] hollow = new byte[2048];
        hollow[0] = 'I'; hollow[1] = 'D'; hollow[2] = '3'; hollow[3] = 4;
        Res r = upload(a[1], a[0], "IELTS", "broken.mp3", hollow, "audio/mpeg");
        assertEquals(422, r.status(), r.raw());
        assertEquals("urn:e9x:problem:media-unreadable", r.problemType());
        // a real file cut off after its first boxes: the container header is there, the movie is not
        byte[] mp4 = file("clip.mp4");
        Res cut = upload(a[1], a[0], "IELTS", "cut.mp4", java.util.Arrays.copyOf(mp4, 60), "video/mp4");
        assertEquals(422, cut.status(), cut.raw());
        assertEquals(rows, mediaRows());
        assertEquals(before, storedObjects());
    }

    @Test
    void sizeLimitComesFromTheKeyAndTheAppEnforcesIt() {
        UUID[] a = authorIn("IELTS");
        int rows = mediaRows();
        // 64KB per media file (e9x.content.media.max-upload-size): a PNG-shaped file just over it is refused with a clear problem
        byte[] big = new byte[(int) MEDIA_LIMIT + 1];
        System.arraycopy(file("pixel.png"), 0, big, 0, file("pixel.png").length);
        Res r = upload(a[1], a[0], "IELTS", "big.png", big, "image/png");
        assertEquals(413, r.status(), r.raw());
        assertEquals("urn:e9x:problem:media-too-large", r.problemType());
        assertTrue(r.body().path("detail").asText().contains("64"), "the limit is named: " + r.raw());
        // the multipart ceiling is set from the import key (128KB here), so the application itself stops a request far above both
        Res huge = upload(a[1], a[0], "IELTS", "huge.png", new byte[300 * 1024], "image/png");
        assertEquals(413, huge.status(), huge.raw());
        assertEquals("urn:e9x:problem:payload-too-large", huge.problemType(), "refused by the multipart limit, before the controller looked at the file");
        assertEquals(rows, mediaRows());
        // exactly at the limit is fine
        byte[] edge = new byte[(int) MEDIA_LIMIT];
        System.arraycopy(file("pixel.png"), 0, edge, 0, file("pixel.png").length);
        assertEquals(200, upload(a[1], a[0], "IELTS", "edge.png", edge, "image/png").status());
    }

    @Test
    void onlyTheAuthorOfTheExamInTheContextUploads() {
        UUID[] a = authorIn("IELTS");
        UUID learner = user("learner");
        grant(learner, a[0], null, "LEARNER");
        UUID reviewer = user("reviewer");
        grant(reviewer, a[0], "IELTS", "REVIEWER");
        UUID toeicAuthor = user("toeic");
        grant(toeicAuthor, a[0], "TOEIC", "AUTHOR");
        UUID otherOrg = org("CENTER");
        member(otherOrg, a[1]);
        int rows = mediaRows();

        assertEquals(401, upload(null, null, "IELTS", "tone.mp3").status());
        assertEquals(403, upload(learner, a[0], "IELTS", "tone.mp3").status(), "a learner");
        assertEquals(403, upload(learner, a[0], "IELTS", "big.png", new byte[300 * 1024], "image/png").status(),
                "refused before the body is read: a 413 here would mean the body was parsed (spooled to disk) for somebody who may not upload");
        assertEquals(403, upload(reviewer, a[0], "IELTS", "tone.mp3").status(), "a reviewer reads and never uploads");
        assertEquals(403, upload(toeicAuthor, a[0], "IELTS", "tone.mp3").status(), "the author of another exam");
        assertEquals(403, upload(a[1], otherOrg, "IELTS", "tone.mp3").status(), "the same author in a context where they hold no role");
        assertEquals(403, upload(a[1], null, "IELTS", "tone.mp3").status(), "or in the personal context");
        assertEquals(403, upload(a[1], a[0], "TOEIC", "tone.mp3").status(), "for an exam they are no author of");
        assertEquals(403, upload(a[1], a[0], "NOPE", "tone.mp3").status(), "an unknown exam");
        assertEquals(rows, mediaRows());
    }

    @Test
    void englishNineXAuthorUploadsEnglishNineXMedia() {
        UUID author = user("e9-author");
        grant(author, null, "IELTS", "AUTHOR");
        Res r = upload(author, null, "IELTS", "tone.mp3");
        assertEquals(200, r.status(), r.raw());
        assertEquals(0, su.queryForObject("select count(*) from media_assets where id = ? and owner_org_id is not null", Integer.class, UUID.fromString(r.body().path("id").asText())));
    }

    @Test
    void aRequestWithoutAFileOrWithTheWrongShapeIsAProblem() {
        UUID[] a = authorIn("IELTS");
        Res none = call(HttpMethod.POST, "/api/cms/media?examCode=IELTS", "{}", a[1], a[0]);
        assertEquals(415, none.status(), none.raw());
        Res empty = upload(a[1], a[0], "IELTS", "empty.mp3", new byte[0], "audio/mpeg");
        assertEquals(400, empty.status(), empty.raw());
        assertFalse(empty.raw().contains("Exception"));
        assertEquals(400, upload(a[1], a[0], "", "tone.mp3").status());
    }

    @Test
    void limitsTellTheScreenWhatItMayOffer() {
        UUID[] a = authorIn("IELTS");
        Res r = get("/api/cms/media/limits", a[1], a[0]);
        assertEquals(200, r.status(), r.raw());
        assertEquals(MEDIA_LIMIT, r.body().path("maxUploadBytes").asLong(), "the configured key, not a number of the screen's own");
        List<String> ext = new java.util.ArrayList<>();
        r.body().path("extensions").forEach(n -> ext.add(n.asText()));
        assertTrue(ext.containsAll(List.of("mp3", "m4a", "webm", "ogg", "png", "jpg", "jpeg", "webp", "mp4")), ext.toString());
        assertFalse(ext.contains("exe") || ext.contains("gif") || ext.contains("wav"), ext.toString());
        assertEquals(401, get("/api/cms/media/limits", null, null).status());
    }
}
