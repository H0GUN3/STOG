package com.stog.backend.plan;

import com.stog.backend.storage.GcsReadUrlSigner;

final class CatalogImageUrl {
    private CatalogImageUrl() {
    }

    static String resolve(
        String sourceUrl,
        String objectKey,
        GcsReadUrlSigner signer
    ) {
        if (objectKey == null || objectKey.isBlank() || signer == null) {
            return sourceUrl;
        }
        try {
            return signer.issueReadUrl(objectKey).toString();
        } catch (RuntimeException error) {
            return sourceUrl;
        }
    }
}
