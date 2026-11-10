package com.english9x.content.media.internal;

import com.english9x.content.media.api.MediaKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * F12. Both routes are synchronous request-thread code: the upload copies the part to a temp file and the link is signed in memory, so
 * nothing here streams or dispatches asynchronously, and {@code CurrentContextFilter}'s binding (thread-bound, skipped on async dispatch)
 * is the one every other controller relies on.
 */
@RestController
class MediaController {

    record MediaView(UUID id, MediaKind kind, String mimeType, long sizeBytes, Long durationMs, String originalFilename, String examCode) {}

    record MediaLink(String url, Instant expiresAt) {}

    @Schema(name = "MediaUploadForm")
    record UploadForm(@Schema(type = "string", format = "binary", description = "the file; its type is read from its bytes") String file) {}

    /** What the upload screen may offer and check before sending a byte; the server checks again whatever the screen did. */
    record MediaLimits(long maxUploadBytes, java.util.List<String> extensions) {}

    private final MediaService media;
    private final UploadLimits limits;

    MediaController(MediaService media, UploadLimits limits) {
        this.media = media;
        this.limits = limits;
    }

    @GetMapping("/api/cms/media/limits")
    MediaLimits limits() {
        return new MediaLimits(limits.maxUploadSize().toBytes(), MediaSniffer.Container.allExtensions());
    }

    /**
     * {@code POST /api/cms/media?examCode=IELTS}, multipart with the part {@code file}. The exam is a QUERY parameter on purpose: a form field
     * would make the framework read the whole body before this method runs (resolve-lazily=true is what lets the author be checked first).
     */
    @PostMapping(path = "/api/cms/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @io.swagger.v3.oas.annotations.Parameter(name = "examCode", in = io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, required = true,
            description = "the exam the file is for; the caller must be an AUTHOR of it in the current context", schema = @Schema(type = "string"))
    @Operation(summary = "Upload an audio, image or video file for an exam",
            requestBody = @RequestBody(content = @Content(mediaType = "multipart/form-data", schema = @Schema(implementation = UploadForm.class))))
    MediaView upload(MultipartHttpServletRequest request) {
        String examCode = UriComponentsBuilder.fromUriString("?" + (request.getQueryString() == null ? "" : request.getQueryString()))
                .build().getQueryParams().getFirst("examCode");
        MediaService.Uploaded u = media.upload(examCode == null ? null : java.net.URLDecoder.decode(examCode, java.nio.charset.StandardCharsets.UTF_8),
                request::getFile);
        return new MediaView(u.id(), u.kind(), u.mimeType(), u.sizeBytes(), u.durationMs(), u.filename(), u.examCode());
    }

    /** A signed, expiring link to the file for its exam's author or reviewer; 404 for anybody else, learners included (P3.1 opens that path). */
    @GetMapping("/api/media/{id}/url")
    ResponseEntity<MediaLink> link(@PathVariable UUID id) {
        Optional<com.english9x.content.media.api.MediaUrls.SignedUrl> link = media.linkFor(id);
        MediaLink body = link.map(l -> new MediaLink(l.url().toString(), l.expiresAt())).orElseThrow(MediaProblems::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
