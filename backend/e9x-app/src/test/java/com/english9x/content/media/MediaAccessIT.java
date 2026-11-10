package com.english9x.content.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.content.media.api.MediaUrls;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** GET /api/media/{id}/url (F12): a signed link for the exam's author, nobody else, expiring after e9x.media.url-ttl. */
class MediaAccessIT extends MediaIT {

    @Autowired
    MediaUrls urls;

    private UUID uploaded(UUID[] author, String name) {
        Res r = upload(author[1], author[0], "IELTS", name);
        assertEquals(200, r.status(), r.raw());
        return UUID.fromString(r.body().path("id").asText());
    }

    private Res url(UUID id, UUID asUser, UUID inOrg) {
        return get("/api/media/" + id + "/url", asUser, inOrg);
    }

    @Test
    void theAuthorGetsALinkThatServesTheStoredBytes() {
        UUID[] a = authorIn("IELTS");
        UUID id = uploaded(a, "tone.mp3");
        Res r = url(id, a[1], a[0]);
        assertEquals(200, r.status(), r.raw());
        String link = r.body().path("url").asText();
        assertTrue(link.contains("X-Amz-Signature="), link);
        assertTrue(link.contains("/" + id + "?"), "the object key is the row id, never the client's name: " + link);
        assertFalse(link.contains("tone"), link);
        assertTrue(link.contains("X-Amz-Expires=600"), "e9x.media.url-ttl defaults to 10 minutes: " + link);
        Instant expires = Instant.parse(r.body().path("expiresAt").asText());
        assertTrue(expires.isAfter(Instant.now().plusSeconds(500)) && expires.isBefore(Instant.now().plusSeconds(605)), expires.toString());
        assertTrue(r.headers().getFirst("Cache-Control").contains("no-store"), "a link is not cached");

        HttpResponse<byte[]> got = fetch(link);
        assertEquals(200, got.statusCode());
        assertArrayEquals(file("tone.mp3"), got.body(), "the very bytes that were uploaded");
        assertEquals("audio/mpeg", got.headers().firstValue("Content-Type").orElse(""), "the type the server found, not the client's");
    }

    @Test
    void urlExpiresAfterTtl() throws Exception {
        UUID[] a = authorIn("IELTS");
        UUID id = uploaded(a, "tone.mp3");
        String link = urls.signedUrl(id, Duration.ofSeconds(2)).toString();
        assertTrue(link.contains("X-Amz-Expires=2"), link);
        assertEquals(200, fetch(link).statusCode(), "valid inside the ttl");
        Thread.sleep(3500);
        HttpResponse<byte[]> late = fetch(link);
        assertEquals(403, late.statusCode(), "rejected once expired");
        assertTrue(new String(late.body()).contains("expired"), new String(late.body()));
        // a fresh link for the same object works again: the object was not touched
        assertEquals(200, fetch(urls.signedUrl(id, Duration.ofSeconds(60)).toString()).statusCode());
    }

    @Test
    void aLinkCannotBeEditedIntoAnotherOne() {
        UUID[] a = authorIn("IELTS");
        UUID first = uploaded(a, "tone.mp3");
        UUID second = uploaded(a, "tone.ogg");
        String link = url(first, a[1], a[0]).body().path("url").asText();
        // another object under the same signature, and a longer life under the same signature: both fail the signature check
        assertEquals(403, fetch(link.replace(first.toString(), second.toString())).statusCode());
        assertEquals(403, fetch(link.replace("X-Amz-Expires=600", "X-Amz-Expires=604800")).statusCode());
        assertEquals(403, fetch(link.substring(0, link.indexOf('?'))).statusCode(), "the bucket is private: no link, no object");
    }

    @Test
    void learnerWithoutEntitlementDenied() {
        UUID[] a = authorIn("IELTS");
        UUID id = uploaded(a, "tone.mp3");
        UUID learner = user("learner");
        grant(learner, a[0], null, "LEARNER");
        // until P3.1 every learner is refused, with an entitlement or without one (it has no lessons to be entitled through yet)
        Res own = url(id, learner, a[0]);
        assertEquals(404, own.status(), own.raw());
        assertFalse(own.raw().contains("X-Amz"), own.raw());
        Res personal = url(id, learner, null);
        assertEquals(404, personal.status(), personal.raw());
        // a teacher of the organisation is no author either
        UUID teacher = user("teacher");
        grant(teacher, a[0], "IELTS", "TEACHER");
        assertEquals(404, url(id, teacher, a[0]).status());
        // and the same answer for an id that does not exist: nothing tells "not yours" from "not there"
        assertEquals(404, url(UUID.randomUUID(), a[1], a[0]).status());
        assertEquals(url(UUID.randomUUID(), learner, a[0]).body().path("type").asText(), url(id, learner, a[0]).body().path("type").asText());
    }

    @Test
    void anotherOrganisationsAuthorAndAnotherExamsAuthorAreDenied() {
        UUID[] a = authorIn("IELTS");
        UUID id = uploaded(a, "tone.mp3");
        UUID[] b = authorIn("IELTS");
        assertEquals(404, url(id, b[1], b[0]).status(), "an author of another organisation");
        UUID toeic = user("toeic");
        grant(toeic, a[0], "TOEIC", "AUTHOR");
        assertEquals(404, url(id, toeic, a[0]).status(), "an author of another exam of the same organisation");
        UUID e9 = user("e9");
        grant(e9, null, "IELTS", "AUTHOR");
        assertEquals(404, url(id, e9, null).status(), "English9x's author does not open an organisation's media");
        assertEquals(401, url(id, null, null).status());
        // the author in the wrong context: the same person, a context where the file does not belong
        member(b[0], a[1]);
        assertEquals(404, url(id, a[1], b[0]).status(), "owner and context both count");
        assertEquals(404, url(id, a[1], null).status());
    }

    @Test
    void aReviewerOfTheExamMayListenAndEnglishNineXMediaFollowsTheSameRule() {
        UUID[] a = authorIn("IELTS");
        UUID id = uploaded(a, "tone.mp3");
        UUID reviewer = user("reviewer");
        grant(reviewer, a[0], "IELTS", "REVIEWER");
        Res r = url(id, reviewer, a[0]);
        assertEquals(200, r.status(), r.raw());
        assertEquals(200, fetch(r.body().path("url").asText()).statusCode());

        UUID e9 = user("e9");
        grant(e9, null, "IELTS", "AUTHOR");
        UUID e9Media = UUID.fromString(upload(e9, null, "IELTS", "pixel.png").body().path("id").asText());
        assertEquals(200, url(e9Media, e9, null).status());
        assertEquals(404, url(e9Media, a[1], a[0]).status(), "an organisation's author does not open English9x's media");
        UUID e9Reviewer = user("e9r");
        grant(e9Reviewer, null, "IELTS", "REVIEWER");
        assertEquals(200, url(e9Media, e9Reviewer, null).status());
    }

    @Test
    void defaultLifetimeIsTheConfiguredOne() {
        // MediaUrls#signedUrl(id) uses e9x.media.url-ttl (10m by default)
        UUID id = UUID.randomUUID();
        assertTrue(urls.signedUrl(id).toString().contains("X-Amz-Expires=600"));
        assertNotEquals(urls.signedUrl(id).toString(), urls.signedUrl(UUID.randomUUID()).toString());
    }
}
