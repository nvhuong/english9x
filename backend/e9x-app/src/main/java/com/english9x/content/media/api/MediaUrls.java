package com.english9x.content.media.api;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * A signed, time-limited link to a stored file (spec 7.5: the files are private, nobody holds a permanent link).
 *
 * <p>The link is made for the public address of the site and goes through the same-origin route of the reverse proxy
 * ({@code /<bucket>/<id>?X-Amz-...}), so a page loads it under {@code media-src 'self'}. It proves nothing about the caller: <b>this
 * method signs for whoever calls it</b>. The caller decides who may listen first: the CMS endpoint {@code GET /api/media/{id}/url} asks the
 * database (row-level security: the author or reviewer of the exam), the exam room (P3.3) and entitled learners (P3.1) check their own rules.
 * It never reveals whether the file exists; a link for a file that is not there simply fails when it is fetched.
 */
public interface MediaUrls {

    /** A link valid for {@code ttl} (at least one second, at most seven days). */
    SignedUrl signedUrl(UUID mediaId, Duration ttl);

    /** A link valid for {@code e9x.media.url-ttl} (10 minutes by default). */
    SignedUrl signedUrl(UUID mediaId);

    /** @param expiresAt when the link stops working (the signature carries the same limit; this is for the client's clock) */
    record SignedUrl(URI url, java.time.Instant expiresAt) {
        @Override
        public String toString() {
            return url.toString();
        }
    }
}
