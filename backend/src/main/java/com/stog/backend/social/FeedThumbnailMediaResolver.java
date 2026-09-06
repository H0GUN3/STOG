package com.stog.backend.social;

import com.stog.backend.storage.GcsReadUrlSigner;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class FeedThumbnailMediaResolver {
    private final ObjectProvider<GcsReadUrlSigner> signedReads;

    public FeedThumbnailMediaResolver(ObjectProvider<GcsReadUrlSigner> signedReads) {
        this.signedReads = signedReads;
    }

    public FeedResponses.ThumbnailMedia resolve(
        boolean signedReadEligible,
        String thumbnailKey
    ) {
        if (!signedReadEligible) {
            return FeedResponses.ThumbnailMedia.unavailable();
        }
        GcsReadUrlSigner signer = signedReads.getIfAvailable();
        if (signer == null) {
            return FeedResponses.ThumbnailMedia.unavailable();
        }
        try {
            URI signedUrl = signer.issueReadUrl(thumbnailKey);
            return FeedResponses.ThumbnailMedia.available(signedUrl);
        } catch (RuntimeException error) {
            return FeedResponses.ThumbnailMedia.failed();
        }
    }

    public URI resolveProfileImage(String profileImageKey) {
        if (profileImageKey == null || profileImageKey.isBlank()) {
            return null;
        }
        GcsReadUrlSigner signer = signedReads.getIfAvailable();
        if (signer == null) {
            return null;
        }
        try {
            return signer.issueReadUrl(profileImageKey);
        } catch (RuntimeException error) {
            return null;
        }
    }
}
