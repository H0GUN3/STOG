package com.stog.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SocialAccountServiceTest {
    @Autowired
    private AuthService auth;

    @Autowired
    private AuthAccountRepository accounts;

    @Test
    void verifiedProviderIdentityCreatesAndReusesOneAccount() {
        SocialProfile profile = new SocialProfile("naver", "social-probe-" + Instant.now(), "소셜 사용자");

        AuthSession first = auth.social(profile);
        AuthSession second = auth.social(profile);

        assertThat(first.user().getId()).isEqualTo(second.user().getId());
        assertThat(accounts.findByProviderAndProviderUserId(
            "naver",
            profile.providerUserId()
        )).isPresent();
    }
}
