package com.stog.backend.social;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stog.social.feed")
public record FeedProperties(int pageSize) {
    public FeedProperties {
        if (pageSize < 1 || pageSize == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("pageSize must be a positive bounded integer");
        }
    }
}
