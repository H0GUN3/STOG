package com.stog.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stog.backend.cell.CellIdCalculator;
import com.stog.backend.plan.EffectiveVisibilityService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PhotoReadServiceTest {
    private PhotoRepository photos;
    private EffectiveVisibilityService visibility;
    private FixedGcsObjectClient objects;
    private PhotoReadService reads;

    @BeforeEach
    void setUp() {
        photos = mock(PhotoRepository.class);
        visibility = mock(EffectiveVisibilityService.class);
        objects = new FixedGcsObjectClient();
        GcsSignedUrlService signer = new GcsSignedUrlService(
            objects,
            new StorageProperties("private-bucket", Duration.ofMinutes(5), "",
                org.springframework.util.unit.DataSize.ofMegabytes(2),
                org.springframework.util.unit.DataSize.ofKilobytes(50)),
            Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC)
        );
        reads = new PhotoReadService(photos, visibility, signer);
    }

    @Test
    void deniedViewerReceivesNoSignedRead() {
        when(visibility.canReadPhoto(8L, 11L)).thenReturn(false);

        assertThatThrownBy(() -> reads.get(8L, 11L))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        verifyNoInteractions(photos);
        assertThat(objects.signedReadObjectKeys()).isEmpty();
    }

    @Test
    void authorizedArchiveReturnsExactLocatedAndCoordinateLessMetadataAndSignsOnlyReadableKeys() {
        PhotoRepository.StoredPhoto readable = setLogPhoto(11L, 7L, true);
        PhotoRepository.StoredPhoto hidden = photo(12L, 8L);
        when(visibility.canReadTrip(7L, 9L)).thenReturn(true);
        when(photos.findByTrip(9L)).thenReturn(List.of(readable, hidden));
        when(visibility.canReadPhoto(7L, 11L)).thenReturn(true);
        when(visibility.canReadPhoto(7L, 12L)).thenReturn(false);
        objects.put(readable.thumbKey(), "image/jpeg", "thumbnail");

        List<PhotoResponses.ArchiveItem> archive = reads.archive(7L, 9L);

        assertThat(archive).extracting(PhotoResponses.ArchiveItem::id).containsExactly(11L);
        PhotoResponses.ArchiveItem item = archive.get(0);
        assertThat(item.thumbnail_url().toString())
            .isEqualTo("https://storage.test/read/" + readable.thumbKey());
        assertThat(item.accuracy_m()).isEqualTo(12.5);
        assertThat(item.caption()).isEqualTo("Immutable Set Log note");
        assertThat(item.place_id()).isEqualTo(91L);
        assertThat(item.place_name()).isEqualTo("Immutable place snapshot");
        assertThat(item.place_resolution_status()).isEqualTo("matched");
        assertThat(item.visibility()).isEqualTo("public");
        assertThat(item.moderation_status()).isEqualTo("approved");
        assertThat(item.publication_status()).isEqualTo("public");
        assertThat(objects.signedReadObjectKeys()).containsExactly(readable.thumbKey());
    }

    @Test
    void authorizedDetailSignsBothObjectsWithoutProxyingTheirBytes() {
        PhotoRepository.StoredPhoto photo = photo(11L, 7L);
        when(visibility.canReadPhoto(7L, 11L)).thenReturn(true);
        when(photos.find(11L)).thenReturn(Optional.of(photo));
        objects.put(photo.originalKey(), "image/jpeg", "normalized-original");
        objects.put(photo.thumbKey(), "image/jpeg", "thumbnail");

        PhotoResponses.Detail detail = reads.get(7L, 11L);

        assertThat(detail.original_url().toString()).startsWith("https://storage.test/read/");
        assertThat(detail.thumbnail_url().toString()).startsWith("https://storage.test/read/");
        assertThat(detail.source()).isEqualTo("camera");
        assertThat(detail.taken_at()).isEqualTo(Instant.parse("2026-08-25T00:00:00Z"));
        assertThat(objects.signedReadObjectKeys())
            .containsExactly(photo.originalKey(), photo.thumbKey());
    }

    @Test
    void coordinateLessGalleryRemainsInAuthorizedDetailAndMissingObjectFailsWithoutSigning() {
        PhotoRepository.StoredPhoto gallery = setLogPhoto(13L, 7L, false);
        when(visibility.canReadPhoto(7L, 13L)).thenReturn(true);
        when(photos.find(13L)).thenReturn(Optional.of(gallery));

        assertThatThrownBy(() -> reads.get(7L, 13L))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(photos).find(13L);
        assertThat(objects.signedReadObjectKeys()).isEmpty();

        objects.put(gallery.originalKey(), "image/jpeg", "normalized-original");
        objects.put(gallery.thumbKey(), "image/jpeg", "thumbnail");
        PhotoResponses.Detail detail = reads.get(7L, 13L);
        assertThat(detail.source()).isEqualTo("gallery");
        assertThat(detail.cell_id()).isNull();
        assertThat(detail.latitude()).isNull();
        assertThat(detail.longitude()).isNull();
        assertThat(detail.accuracy_m()).isNull();
        assertThat(detail.taken_at()).isNull();
        assertThat(detail.place_id()).isNull();
        assertThat(detail.place_name()).isNull();
        assertThat(detail.place_resolution_status()).isEqualTo("no_match");
    }

    @Test
    void deniedTripArchiveDoesNotLoadRowsOrIssueSignedReads() {
        when(visibility.canReadTrip(8L, 9L)).thenReturn(false);

        assertThatThrownBy(() -> reads.archive(8L, 9L))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        verifyNoInteractions(photos);
        assertThat(objects.signedReadObjectKeys()).isEmpty();
    }

    private PhotoRepository.StoredPhoto setLogPhoto(long id, long userId, boolean located) {
        String uploadId = "%08d-1111-1111-1111-111111111111".formatted(id);
        return new PhotoRepository.StoredPhoto(
            id,
            9L,
            userId,
            located ? "camera" : "gallery",
            located ? CellIdCalculator.fromCoords(35.815, 127.15) : null,
            located ? 35.815 : null,
            located ? 127.15 : null,
            located ? 12.5 : null,
            located ? "camera_foreground" : null,
            located ? Instant.parse("2026-08-25T00:00:00Z") : null,
            "photos/%d/%s.jpg".formatted(userId, uploadId),
            "photos/%d/%s-thumb.jpg".formatted(userId, uploadId),
            "Immutable Set Log note",
            located ? 91L : null,
            located ? "Immutable place snapshot" : null,
            located ? "matched" : "no_match",
            located ? "public" : "private",
            located ? "approved" : "pending",
            located,
            located ? "public" : "private",
            Instant.parse("2026-08-25T00:00:00Z")
        );
    }

    private PhotoRepository.StoredPhoto photo(long id, long userId) {
        String uploadId = "%08d-1111-1111-1111-111111111111".formatted(id);
        return new PhotoRepository.StoredPhoto(
            id,
            9L,
            userId,
            "camera",
            null,
            null,
            null,
            Instant.parse("2026-08-25T00:00:00Z"),
            "photos/%d/%s.jpg".formatted(userId, uploadId),
            "photos/%d/%s-thumb.jpg".formatted(userId, uploadId),
            null,
            "private",
            "pending",
            Instant.parse("2026-08-25T00:00:00Z")
        );
    }
}
