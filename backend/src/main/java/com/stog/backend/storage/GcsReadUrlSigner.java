package com.stog.backend.storage;

import java.net.URI;

public interface GcsReadUrlSigner {
    URI issueReadUrl(String objectKey);
}
