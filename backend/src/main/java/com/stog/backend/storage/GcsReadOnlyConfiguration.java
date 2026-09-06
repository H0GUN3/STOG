package com.stog.backend.storage;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("gcs-read & !gcs-write")
class GcsReadOnlyConfiguration {
    @Bean
    GcsReadUrlSigner gcsReadUrlSigner(
        GcsObjectClient objects,
        StorageProperties properties,
        Clock clock
    ) {
        GcsSignedUrlService delegate = new GcsSignedUrlService(objects, properties, clock);
        return delegate::issueReadUrl;
    }
}
