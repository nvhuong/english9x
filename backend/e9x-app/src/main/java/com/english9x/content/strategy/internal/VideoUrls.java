package com.english9x.content.strategy.internal;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Reads the video id out of a YouTube or Vimeo address. The host is checked by the caller against the allowlist; this only parses. */
final class VideoUrls {

    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{6,20}");
    private static final Pattern VIMEO_ID = Pattern.compile("[0-9]{4,12}");

    private VideoUrls() {}

    /** The host of an https address, lower-cased; empty for anything else (other scheme, user info, no host, unparsable). */
    static Optional<String> httpsHost(String url) {
        try {
            URI u = new URI(url.trim());
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null) {
                return Optional.empty();
            }
            return Optional.of(u.getHost().toLowerCase(Locale.ROOT));
        } catch (java.net.URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** The id of the video, when the address is one of the known shapes of the provider. */
    static Optional<String> videoId(String provider, String url) {
        try {
            URI u = new URI(url.trim());
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            String path = u.getPath() == null ? "" : u.getPath();
            if ("youtube".equals(provider)) {
                if (!host.contains("youtube.com") && !host.equals("youtu.be")) {
                    return Optional.empty();
                }
                String id = null;
                if (host.equals("youtu.be")) {
                    id = path.length() > 1 ? path.substring(1) : null;
                } else if (path.startsWith("/embed/")) {
                    id = path.substring("/embed/".length());
                } else if (path.equals("/watch") && u.getQuery() != null) {
                    for (String kv : u.getQuery().split("&")) {
                        if (kv.startsWith("v=")) {
                            id = kv.substring(2);
                        }
                    }
                }
                return id != null && YOUTUBE_ID.matcher(id).matches() ? Optional.of(id) : Optional.empty();
            }
            if ("vimeo".equals(provider)) {
                if (!host.endsWith("vimeo.com")) {
                    return Optional.empty();
                }
                String last = path.substring(path.lastIndexOf('/') + 1);
                return VIMEO_ID.matcher(last).matches() ? Optional.of(last) : Optional.empty();
            }
            return Optional.empty();
        } catch (java.net.URISyntaxException e) {
            return Optional.empty();
        }
    }
}
