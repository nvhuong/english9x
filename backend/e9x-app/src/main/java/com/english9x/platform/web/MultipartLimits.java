package com.english9x.platform.web;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * spring.servlet.multipart.* defaulted to 1 MB, so every upload was refused (P1 final review). The ceiling is made from the two keys of spec 15.1
 * that name a file size, so there is no number of its own to keep in step:
 * <ul>
 *   <li>a file part may be as large as the larger of {@code e9x.content.media.max-upload-size} (P2.2) and {@code e9x.content.import.max-media-size} (P2.7).
 *       Each endpoint then checks its own, smaller or equal, key;
 *   <li>a request may carry that file plus an envelope (the Excel workbook of an import, boundaries, small fields);
 *   <li>the whole must stay below Caddy's {@code @upload} limit (infra/Caddyfile, 600MB), so the application, not only the proxy, is the one that refuses: the
 *       application refuses to start when the keys would not fit.
 *   <li>a part is always spooled to a temporary file, never held in memory.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class MultipartLimits {

    /** The workbook and the multipart framing around the largest file. */
    static final DataSize ENVELOPE = DataSize.ofMegabytes(16);
    /** infra/Caddyfile: request_body max_size of @upload. */
    static final DataSize CADDY_UPLOAD_CEILING = DataSize.ofMegabytes(600);

    @Bean
    MultipartConfigElement multipartConfigElement(@Value("${e9x.content.media.max-upload-size}") DataSize media,
                                                  @Value("${e9x.content.import.max-media-size}") DataSize imports) {
        return element(media, imports);
    }

    static MultipartConfigElement element(DataSize media, DataSize imports) {
        long file = Math.max(media.toBytes(), imports.toBytes());
        long request = file + ENVELOPE.toBytes();
        if (request > CADDY_UPLOAD_CEILING.toBytes()) {
            throw new IllegalStateException("e9x.content.media.max-upload-size / e9x.content.import.max-media-size (" + DataSize.ofBytes(file)
                    + " plus a " + ENVELOPE + " envelope) do not fit under the proxy's " + CADDY_UPLOAD_CEILING + " upload limit: raise infra/Caddyfile first");
        }
        return new MultipartConfigElement("", file, request, 0);
    }
}
