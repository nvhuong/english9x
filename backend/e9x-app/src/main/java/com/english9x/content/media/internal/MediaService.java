package com.english9x.content.media.internal;

import com.english9x.content.media.api.MediaAssets;
import com.english9x.content.media.api.MediaKind;
import com.english9x.content.media.api.MediaUrls;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Upload (F12). The order is the security argument:
 * <ol>
 *   <li>who: the AUTHOR of the exam in the current context (asked of the database), before a byte of the body is read from the request;
 *   <li>how big: the declared size against {@code e9x.content.media.max-upload-size};
 *   <li>what: the container from the magic number (never the name or the declared type), then the name must agree with it;
 *   <li>how long and which kind: ffprobe on the stored bytes; a file it cannot read is refused, never recorded with a guessed length;
 *   <li>only then the object, under the new row's id, and the row (whose policy asks the database for the same rights once more).
 * </ol>
 * A failure after the object was stored removes the object again.
 */
@Service
class MediaService {

    private static final Logger LOG = LoggerFactory.getLogger(MediaService.class);

    record Uploaded(UUID id, MediaKind kind, String mimeType, long sizeBytes, Long durationMs, String filename, String examCode) {}

    private final Authz authz;
    private final UploadLimits limits;
    private final MediaProbe probe;
    private final ObjectStore store;
    private final MediaRepository repository;
    private final MediaUrls urls;

    MediaService(Authz authz, UploadLimits limits, MediaProbe probe, ObjectStore store, MediaRepository repository, MediaUrls urls) {
        this.authz = authz;
        this.limits = limits;
        this.probe = probe;
        this.store = store;
        this.repository = repository;
        this.urls = urls;
    }

    /** @param part reads the multipart part with the given name; called only after the caller has been authorised */
    Uploaded upload(String examCode, Function<String, MultipartFile> part) {
        if (examCode == null || examCode.isBlank()) {
            throw MediaProblems.examMissing();
        }
        authz.require(Role.AUTHOR, Optional.of(examCode));
        CurrentContextHolder.Binding who = CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no signed-in user"));

        MultipartFile upload = part.apply("file");
        if (upload == null || upload.isEmpty()) {
            throw MediaProblems.fileMissing();
        }
        if (upload.getSize() > limits.maxUploadSize().toBytes()) {
            throw MediaProblems.tooLarge(limits.maxUploadSize());
        }
        String filename = cleanName(upload.getOriginalFilename());
        Path temp = null;
        UUID id = UUID.randomUUID();
        boolean stored = false;
        boolean done = false;
        try {
            temp = Files.createTempFile("e9x-media-", ".upload");
            upload.transferTo(temp.toFile());
            MediaSniffer.Container container = MediaSniffer.sniff(temp).orElseThrow(MediaProblems::notAllowed);
            if (!container.accepts(filename)) {
                throw MediaProblems.extensionMismatch();
            }
            Found found = classify(container, temp);
            try {
                store.put(id.toString(), temp, found.mime());
                stored = true;
                repository.insert(id, who.orgId().orElse(null), examCode, found.kind(), found.mime(), Files.size(temp), found.durationMs(), filename, who.userId());
            } catch (DataAccessException e) {
                throw denied(e);
            }
            done = true;
            return new Uploaded(id, found.kind(), found.mime(), Files.size(temp), found.durationMs(), filename, examCode);
        } catch (MediaUnreadableException e) {
            LOG.info("media refused as unreadable: {}", e.getMessage());
            throw MediaProblems.unreadable();
        } catch (MediaStorageException | MediaProbeUnavailableException e) {
            LOG.error("media storage or probe failed", e);
            throw MediaProblems.storageUnavailable();
        } catch (IOException e) {
            LOG.error("media upload could not be handled", e);
            throw MediaProblems.storageUnavailable();
        } finally {
            if (stored && !done) {
                removeQuietly(id); // the object must not outlive a failed upload
            }
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    LOG.warn("temp file {} could not be removed", temp.getFileName());
                }
            }
        }
    }

    private record Found(MediaKind kind, String mime, Long durationMs) {}

    private Found classify(MediaSniffer.Container c, Path file) {
        switch (c) {
            case PNG:
                return new Found(MediaKind.IMAGE, "image/png", null);
            case JPEG:
                return new Found(MediaKind.IMAGE, "image/jpeg", null);
            case WEBP:
                return new Found(MediaKind.IMAGE, "image/webp", null);
            default:
                break;
        }
        MediaProbe.Result r = probe.probe(file, c);
        return switch (c) {
            case MP3 -> {
                require(r.audio() && !r.video());
                yield new Found(MediaKind.AUDIO, "audio/mpeg", r.durationMs());
            }
            case OGG -> {
                if (r.video()) {
                    throw MediaProblems.notAllowed(); // Ogg video (Theora) is not on the list
                }
                require(r.audio());
                yield new Found(MediaKind.AUDIO, "audio/ogg", r.durationMs());
            }
            case MP4 -> r.video() ? new Found(MediaKind.VIDEO, "video/mp4", r.durationMs()) : audioOnly(r, "audio/mp4");
            case WEBM -> r.video() ? new Found(MediaKind.VIDEO, "video/webm", r.durationMs()) : audioOnly(r, "audio/webm");
            default -> throw new IllegalStateException(c.name());
        };
    }

    private static Found audioOnly(MediaProbe.Result r, String mime) {
        require(r.audio());
        return new Found(MediaKind.AUDIO, mime, r.durationMs());
    }

    private static void require(boolean ok) {
        if (!ok) {
            throw new MediaUnreadableException("the streams do not match the container");
        }
    }

    private void removeQuietly(UUID id) {
        try {
            store.delete(id.toString());
        } catch (RuntimeException e) {
            LOG.error("orphan media object {} could not be removed", id, e);
        }
    }

    private static RuntimeException denied(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLException sql && "42501".equals(sql.getSQLState())) {
                return new AccessDeniedException("not allowed");
            }
        }
        return e;
    }

    /** The base name only, without control characters, at most 255 characters; the name is for display and never reaches a path or a key. */
    static String cleanName(String raw) {
        String name = raw == null ? "" : raw;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            name = "file";
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    /** A link for the file when the caller may see it (row-level security decides), empty when not: the same answer for "not yours" and "not there". */
    Optional<MediaUrls.SignedUrl> linkFor(UUID id) {
        return repository.find(id).map(info -> urls.signedUrl(id));
    }
}
