package com.stog.backend.storage;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile({"gcs-read", "gcs-write"})
public class GcsStorageConfiguration {
    private static final List<String> STORAGE_SCOPES =
        List.of("https://www.googleapis.com/auth/cloud-platform");
    @Bean
    GoogleCredentials gcsCredentials(StorageProperties properties) {
        try {
            return signerCredentials(
                GoogleCredentials.getApplicationDefault(),
                properties.signerServiceAccount()
            );
        } catch (IOException error) {
            throw new IllegalStateException("GCS credentials could not be initialized", error);
        }
    }

    @Bean
    Storage storage(GoogleCredentials credentials) {
        return StorageOptions.newBuilder()
            .setCredentials(credentials)
            .build()
            .getService();
    }

    static StorageOptions storageOptions(
        GoogleCredentials source,
        String signerServiceAccount
    ) throws IOException {
        return StorageOptions.newBuilder()
            .setCredentials(signerCredentials(source, signerServiceAccount))
            .build();
    }

    static GoogleCredentials signerCredentials(
        GoogleCredentials source,
        String signerServiceAccount
    ) throws IOException {
        GoogleCredentials sourceWithoutQuotaProject =
            source instanceof ImpersonatedCredentials && source.getQuotaProjectId() == null
                ? source
                : source.createWithQuotaProject(null);
        if (signerServiceAccount == null || signerServiceAccount.isBlank()) {
            return sourceWithoutQuotaProject;
        }
        if (sourceWithoutQuotaProject instanceof ImpersonatedCredentials impersonated
            && signerServiceAccount.equals(impersonated.getAccount())) {
            return sourceWithoutQuotaProject.createScoped(STORAGE_SCOPES);
        }
        return ImpersonatedCredentials.create(
            sourceWithoutQuotaProject,
            signerServiceAccount,
            List.of(),
            STORAGE_SCOPES,
            (int) Duration.ofHours(1).toSeconds()
        );
    }
}
