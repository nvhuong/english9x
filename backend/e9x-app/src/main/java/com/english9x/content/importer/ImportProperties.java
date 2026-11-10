package com.english9x.content.importer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code e9x.content.import.*} (spec 15.1). {@code maxRows}: the most data rows one workbook may hold; {@code maxMediaSize}: the most the media zip may be, both
 * as uploaded and once unpacked. One row makes one question version, so {@code maxRows} must not exceed {@code e9x.workflow.max-bundle-size}
 * (checked in {@link ImportConfiguration}, where the application refuses to start otherwise).
 */
@ConfigurationProperties("e9x.content.import")
public record ImportProperties(@DefaultValue("2000") int maxRows, @DefaultValue("500MB") DataSize maxMediaSize) {

    /** The largest workbook accepted; the multipart envelope (platform.web.MultipartLimits) leaves 16MB for it and the framing. */
    public static final DataSize MAX_WORKBOOK_SIZE = DataSize.ofMegabytes(10);

    public ImportProperties {
        if (maxRows < 1 || maxRows > 5000) {
            throw new IllegalStateException("e9x.content.import.max-rows must be between 1 and 5000");
        }
        if (maxMediaSize.toBytes() < 1024) {
            throw new IllegalStateException("e9x.content.import.max-media-size must be at least 1KB");
        }
    }
}
