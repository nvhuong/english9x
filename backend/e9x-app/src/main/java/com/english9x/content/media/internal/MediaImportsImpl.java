package com.english9x.content.media.internal;

import com.english9x.content.media.api.MediaImports;
import com.english9x.content.media.api.StagedFiles;
import com.english9x.content.media.api.StagedFilesException;
import com.english9x.org.api.CurrentContextHolder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;

/** The media side of the Excel import (P2.7) and the staging area it uses. See the interfaces. */
@Component
class MediaImportsImpl implements MediaImports, StagedFiles {

    private static final Logger LOG = LoggerFactory.getLogger(MediaImportsImpl.class);
    private static final Pattern KEY = Pattern.compile("staging/[a-z0-9][a-z0-9/._-]{0,200}");

    private final MediaService service;
    private final ObjectStore store;

    MediaImportsImpl(MediaService service, ObjectStore store) {
        this.service = service;
        this.store = store;
    }

    @Override
    public Inspection inspect(Path file, String filename, StringBuilder refusal) {
        try {
            MediaService.Found f = service.inspect(file, MediaService.cleanName(filename));
            return new Inspection(f.kind(), f.mime(), f.durationMs());
        } catch (ErrorResponseException e) {
            refusal.append(e.getBody().getDetail() == null ? "file refused" : e.getBody().getDetail());
        } catch (MediaUnreadableException e) {
            refusal.append("the file cannot be read as the media it claims to be");
        } catch (MediaProbeUnavailableException | MediaStorageException | IOException e) {
            LOG.error("media probe failed during an import", e);
            throw new IllegalStateException("media probe unavailable", e); // a fault of ours, not a verdict on the file: the job retries
        }
        return null;
    }

    @Override
    public UUID store(String examCode, Path file, String filename) {
        CurrentContextHolder.Binding who = CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no signed-in user"));
        return service.storeFile(examCode, who, file, MediaService.cleanName(filename)).id();
    }

    @Override
    public void discard(UUID mediaId) {
        service.discardObject(mediaId);
    }

    // ---- staging -------------------------------------------------------------------------------------------------------------------

    @Override
    public void put(String key, Path file, String contentType) {
        try {
            store.put(checked(key), file, contentType);
        } catch (MediaStorageException e) {
            throw new StagedFilesException("cannot stage a file", e);
        }
    }

    @Override
    public void get(String key, Path target) {
        try {
            store.get(checked(key), target);
        } catch (MediaStorageException e) {
            throw new StagedFilesException("cannot read a staged file", e);
        }
    }

    @Override
    public void deleteQuietly(String key) {
        try {
            store.delete(checked(key));
        } catch (RuntimeException e) {
            LOG.warn("staged object {} could not be removed", key);
        }
    }

    private static String checked(String key) {
        if (key == null || !KEY.matcher(key).matches() || key.contains("..")) {
            throw new IllegalArgumentException("not a staging key");
        }
        return key;
    }
}
