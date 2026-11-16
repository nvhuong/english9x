package com.english9x.content.media.internal;

import com.english9x.content.media.api.MediaAssets;
import com.english9x.content.media.api.MediaFiles;
import com.english9x.content.media.api.StagedFilesException;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** See {@link MediaFiles}. The object key of a file is its id (MediaService#storeFile). */
@Component
class MediaFilesImpl implements MediaFiles {

    private final MediaAssets assets;
    private final ObjectStore store;

    MediaFilesImpl(MediaAssets assets, ObjectStore store) {
        this.assets = assets;
        this.store = store;
    }

    @Override
    public MediaAssets.MediaInfo copyTo(UUID mediaId, Path target) {
        MediaAssets.MediaInfo info = assets.find(mediaId).orElseThrow(() -> new NoSuchElementException("no such file"));
        try {
            store.get(info.id().toString(), target);
        } catch (MediaStorageException e) {
            throw new StagedFilesException("cannot read a stored file", e);
        }
        return info;
    }
}
