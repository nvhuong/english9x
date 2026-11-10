package com.english9x.content.media.internal;

import com.english9x.content.media.api.MediaUrls;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class MediaUrlsImpl implements MediaUrls {

    private final ObjectStore store;
    private final MediaProperties props;
    private final Clock clock;

    MediaUrlsImpl(ObjectStore store, MediaProperties props, Clock clock) {
        this.store = store;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public SignedUrl signedUrl(UUID mediaId, Duration ttl) {
        return new SignedUrl(store.presignGet(mediaId.toString(), ttl), clock.instant().plus(ttl));
    }

    @Override
    public SignedUrl signedUrl(UUID mediaId) {
        return signedUrl(mediaId, props.urlTtl());
    }
}
