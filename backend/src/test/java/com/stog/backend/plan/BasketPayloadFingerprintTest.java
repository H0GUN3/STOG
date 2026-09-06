package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BasketPayloadFingerprintTest {
    @Test
    void fingerprintMatchesAndroidCanonicalEncoding() {
        assertThat(BasketPayloadFingerprint.forPlace(
            11L, "google", "google-1", "첫째", "cafe", 35.8, 127.1, null, null
        )).isEqualTo("cbf94172c51dd02097df2e23fe459abaa89c788902c85ab61e4097ef24182d2c");
    }
}
