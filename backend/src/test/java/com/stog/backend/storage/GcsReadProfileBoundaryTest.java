package com.stog.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

class GcsReadProfileBoundaryTest {
    private static final String THUMBNAIL_KEY =
        "photos/42/11111111-1111-1111-1111-111111111111-thumb.jpg";

    @Test
    void readOnlyProfileHostsSigningInfrastructureWithoutActivatingPhotoWrites() {
        assertThat(GcsStorageConfiguration.class.getAnnotation(Profile.class).value())
            .containsExactly("gcs-read", "gcs-write");
        assertThat(GoogleCloudGcsObjectClient.class.getAnnotation(Profile.class).value())
            .containsExactly("gcs-read", "gcs-write");
        assertThat(GcsReadOnlyConfiguration.class.getAnnotation(Profile.class).value())
            .containsExactly("gcs-read & !gcs-write");
        assertThat(GcsSignedUrlService.class.getAnnotation(Profile.class).value())
            .containsExactly("gcs-write");
        assertThat(PhotoController.class.getAnnotation(Profile.class).value())
            .containsExactly("gcs-write");
        assertThat(PhotoService.class.getAnnotation(Profile.class).value())
            .containsExactly("gcs-write");
        assertThat(PhotoReadService.class.getAnnotation(Profile.class).value())
            .containsExactly("gcs-read", "gcs-write");
        assertThat(PhotoReadController.class.getAnnotation(Profile.class).value())
            .containsExactly("gcs-read", "gcs-write");
        assertThat(TripPhotoController.class.getAnnotation(Profile.class).value())
            .containsExactly("gcs-read", "gcs-write");
    }

    @Test
    void readOnlyAdapterValidatesAndSignsOnlyTheRequestedReadObject() {
        FixedGcsObjectClient objects = new FixedGcsObjectClient();
        objects.put(THUMBNAIL_KEY, "image/jpeg", "thumbnail");
        GcsReadUrlSigner signer = new GcsReadOnlyConfiguration().gcsReadUrlSigner(
            objects,
            new StorageProperties("bucket", Duration.ofMinutes(10), null,
                org.springframework.util.unit.DataSize.ofMegabytes(2),
                org.springframework.util.unit.DataSize.ofKilobytes(50)),
            Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC)
        );

        assertThat(signer.issueReadUrl(THUMBNAIL_KEY)).isEqualTo(
            URI.create("https://storage.test/read/" + THUMBNAIL_KEY)
        );
        assertThat(objects.signedReadObjectKeys()).containsExactly(THUMBNAIL_KEY);
    }
}
