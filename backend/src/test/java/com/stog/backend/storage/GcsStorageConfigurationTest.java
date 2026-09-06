package com.stog.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.storage.StorageOptions;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

class GcsStorageConfigurationTest {
    private static final String SIGNER =
        "stog-dev-gcs-signer@team-05-504502.iam.gserviceaccount.com";

    @Test
    void createsSigningCredentialsForConfiguredServiceAccount() throws Exception {
        GoogleCredentials source = GoogleCredentials.create(
            new AccessToken("source-token", new Date(System.currentTimeMillis() + 3600000))
        );

        GoogleCredentials credentials = GcsStorageConfiguration.signerCredentials(
            source,
            SIGNER
        );

        assertThat(credentials).isInstanceOf(ImpersonatedCredentials.class);
        assertThat(((ImpersonatedCredentials) credentials).getAccount()).isEqualTo(SIGNER);
    }

    @Test
    void keepsSourceCredentialsWhenNoSignerIsConfigured() throws Exception {
        GoogleCredentials source = GoogleCredentials.create(
            new AccessToken("source-token", new Date(System.currentTimeMillis() + 3600000))
        );

        assertThat(GcsStorageConfiguration.signerCredentials(source, ""))
            .isNotSameAs(source)
            .extracting(GoogleCredentials::getQuotaProjectId)
            .isNull();
    }

    @Test
    void reusesAlreadyConfiguredSignerWithoutNestedImpersonation() throws Exception {
        GoogleCredentials base = GoogleCredentials.create(
            new AccessToken("source-token", new Date(System.currentTimeMillis() + 3600000))
        );
        GoogleCredentials source = ImpersonatedCredentials.create(
            base,
            SIGNER,
            List.of(),
            List.of("https://www.googleapis.com/auth/cloud-platform"),
            (int) Duration.ofHours(1).toSeconds()
        ).createWithQuotaProject("team-05-504502");

        GoogleCredentials credentials = GcsStorageConfiguration.signerCredentials(
            source,
            SIGNER
        );

        assertThat(credentials).isInstanceOf(ImpersonatedCredentials.class);
        ImpersonatedCredentials impersonated = (ImpersonatedCredentials) credentials;
        assertThat(impersonated.getAccount()).isEqualTo(SIGNER);
        assertThat(impersonated.getQuotaProjectId()).isNull();
        assertThat(impersonated.getSourceCredentials())
            .isNotInstanceOf(ImpersonatedCredentials.class);
    }

    @Test
    void stillImpersonatesWhenConfiguredSignerDiffersFromSourceAccount() throws Exception {
        GoogleCredentials base = GoogleCredentials.create(
            new AccessToken("source-token", new Date(System.currentTimeMillis() + 3600000))
        );
        ImpersonatedCredentials source = ImpersonatedCredentials.create(
            base,
            "other-signer@team-05-504502.iam.gserviceaccount.com",
            List.of(),
            List.of("https://www.googleapis.com/auth/cloud-platform"),
            (int) Duration.ofHours(1).toSeconds()
        );

        GoogleCredentials credentials = GcsStorageConfiguration.signerCredentials(
            source,
            SIGNER
        );

        assertThat(credentials).isInstanceOf(ImpersonatedCredentials.class);
        ImpersonatedCredentials impersonated = (ImpersonatedCredentials) credentials;
        assertThat(impersonated.getAccount()).isEqualTo(SIGNER);
        assertThat(impersonated.getSourceCredentials()).isInstanceOf(ImpersonatedCredentials.class);
        assertThat(((ImpersonatedCredentials) impersonated.getSourceCredentials()).getAccount())
            .isEqualTo("other-signer@team-05-504502.iam.gserviceaccount.com");
    }

    @Test
    void removesSourceQuotaProjectFromSigningCredentials() throws Exception {
        GoogleCredentials source = GoogleCredentials.create(
            new AccessToken("source-token", new Date(System.currentTimeMillis() + 3600000))
        ).createWithQuotaProject("team-05-504502");

        GoogleCredentials credentials = GcsStorageConfiguration.signerCredentials(
            source,
            SIGNER
        );

        assertThat(credentials.getQuotaProjectId()).isNull();
    }

    @Test
    void removesQuotaProjectFromStorageRequests() throws Exception {
        GoogleCredentials source = GoogleCredentials.create(
            new AccessToken("source-token", new Date(System.currentTimeMillis() + 3600000))
        ).createWithQuotaProject("team-05-504502");

        StorageOptions options = GcsStorageConfiguration.storageOptions(source, SIGNER);

        assertThat(options.getQuotaProjectId()).isNull();
        assertThat(options.getProjectId()).isEqualTo("team-05-504502");
    }
}
