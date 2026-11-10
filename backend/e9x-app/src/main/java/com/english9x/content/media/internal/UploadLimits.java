package com.english9x.content.media.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** {@code e9x.content.media.max-upload-size} (spec 15.1): the most one media file may take. */
@ConfigurationProperties("e9x.content.media")
record UploadLimits(@DefaultValue("200MB") DataSize maxUploadSize) {}
