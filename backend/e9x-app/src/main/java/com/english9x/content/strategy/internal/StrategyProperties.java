package com.english9x.content.strategy.internal;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code e9x.content.strategy.*} (spec 15.1). {@code maxJsonSize}: the most the blocks of one lesson version may be (serialised); {@code maxBlocks}: the
 * most blocks in one lesson; {@code videoHosts}: the hosts a video-embed address may have (YouTube and Vimeo only; the page's CSP frame-src is the
 * browser-side half of the same rule).
 */
@ConfigurationProperties("e9x.content.strategy")
record StrategyProperties(
        @DefaultValue("512KB") DataSize maxJsonSize,
        @DefaultValue("200") int maxBlocks,
        @DefaultValue({"youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "vimeo.com", "www.vimeo.com", "player.vimeo.com"}) List<String> videoHosts) {

    StrategyProperties {
        if (maxJsonSize.toBytes() < 1024 || maxJsonSize.toBytes() > 8 * 1024 * 1024) {
            throw new IllegalStateException("e9x.content.strategy.max-json-size must be between 1KB and 8MB");
        }
        if (maxBlocks < 1 || maxBlocks > 1000) {
            throw new IllegalStateException("e9x.content.strategy.max-blocks must be between 1 and 1000");
        }
        videoHosts = videoHosts.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).toList();
    }

    boolean allows(String host) {
        return host != null && videoHosts.contains(host.toLowerCase(Locale.ROOT));
    }
}
