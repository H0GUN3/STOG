package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EffectiveVisibilityUnitTest {
    @Test
    void publicTripAndPhotoDoNotWaitForModerationApprovalOrGrant() {
        assertThat(EffectiveVisibility.isPubliclyEligible("public", "public", "pending"))
            .isTrue();
    }

    @Test
    void blockedPublicPhotoRemainsHidden() {
        assertThat(EffectiveVisibility.isPubliclyEligible("public", "public", "blocked"))
            .isFalse();
    }

    @Test
    void feedEligibilityRequiresPublicTripAndPhotoOnly() {
        assertThat(EffectiveVisibility.FEED_PHOTO_ELIGIBILITY_SQL)
            .contains("t.visibility = 'public'", "p.visibility = 'public'")
            .doesNotContain("p.moderation_status = 'approved'", "photo_public_grants");
    }
}
