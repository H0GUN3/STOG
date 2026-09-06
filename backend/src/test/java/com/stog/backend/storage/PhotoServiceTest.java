package com.stog.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.stog.backend.cell.CellIdCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stog.backend.plan.EffectiveVisibilityService;
import com.stog.backend.plan.TripMembershipPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PhotoServiceTest {
    private static final Instant TAKEN_AT = Instant.parse("2026-08-19T00:00:00Z");

    private PhotoRepository photos;
    private TripMembershipPolicy memberships;
    private EffectiveVisibilityService visibility;
    private FixedGcsObjectClient objects;
    private GcsSignedUrlService storage;
    private PhotoPlaceResolver placeResolver;
    private PhotoService service;

    @BeforeEach
    void setUp() {
        photos = mock(PhotoRepository.class);
        memberships = mock(TripMembershipPolicy.class);
        visibility = mock(EffectiveVisibilityService.class);
        objects = new FixedGcsObjectClient();
        placeResolver = mock(PhotoPlaceResolver.class);
        storage = new GcsSignedUrlService(
            objects,
            new StorageProperties("stog-test-media", Duration.ofMinutes(5), "",
                org.springframework.util.unit.DataSize.ofMegabytes(2),
                org.springframework.util.unit.DataSize.ofKilobytes(50)),
            Clock.fixed(TAKEN_AT, ZoneOffset.UTC)
        );
        service = new PhotoService(
            photos,
            storage,
            memberships,
            visibility,
            new PhotoPolicyService(photos),
            placeResolver
        );
    }

    private PhotoRequests.Create setLogRequest() {
        PhotoRequests.Create legacy = request();
        return new PhotoRequests.Create(
            legacy.trip_id(), legacy.source(), legacy.client_upload_id(),
            legacy.original_key(), legacy.thumb_key(), legacy.original(), legacy.thumbnail(),
            legacy.latitude(), legacy.longitude(), 21.5, "camera_foreground",
            legacy.taken_at(), "Set Log note", "matched", 91L, "private", false
        );
    }

    private PhotoRequests.Create copy(
        PhotoRequests.Create source,
        String caption,
        Double accuracy,
        String placeStatus,
        Long placeId,
        String visibility,
        Boolean consent
    ) {
        return new PhotoRequests.Create(
            source.trip_id(), source.source(), source.client_upload_id(), source.original_key(),
            source.thumb_key(), source.original(), source.thumbnail(), source.latitude(),
            source.longitude(), accuracy, source.location_provenance(), source.taken_at(), caption,
            placeStatus, placeId, visibility, consent
        );
    }

    private void assertBadRequest(PhotoRequests.Create request) {
        assertThatThrownBy(() -> service.create(7L, request))
            .isInstanceOf(PhotoApiException.class)
            .extracting(error -> ((PhotoApiException) error).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsSetLogCaptionLocationPlaceAndVisibilityValidationFailures() {
        PhotoRequests.Create base = setLogRequest();

        assertBadRequest(copy(base, "x".repeat(121), base.accuracy_m(),
            base.place_resolution_status(), base.expected_place_id(),
            base.visibility(), base.public_consent()));
        assertBadRequest(copy(base, "line one\nline two", base.accuracy_m(),
            base.place_resolution_status(), base.expected_place_id(),
            base.visibility(), base.public_consent()));
        assertBadRequest(copy(base, base.caption(), 100.01, base.place_resolution_status(),
            base.expected_place_id(), base.visibility(), base.public_consent()));
        assertBadRequest(copy(base, base.caption(), base.accuracy_m(), "matched", null,
            base.visibility(), base.public_consent()));
        assertBadRequest(copy(base, base.caption(), base.accuracy_m(),
            base.place_resolution_status(), base.expected_place_id(), "public", false));

        verify(photos, never()).createIfAbsent(eq(7L), any(), any(), any(), any(), any());
        verify(photos, never()).createNextGrant(anyLong(), anyLong());
    }

    @Test
    void changedSetLogReplayReturnsTypedConflictWithoutAnotherPhotoOrGrant() {
        PhotoRequests.Create request = setLogRequest();
        PhotoRepository.StoredPhoto stored = stored(
            CellIdCalculator.fromCoords(35.815, 127.15), "private"
        );
        when(photos.findFinalized(7L, request.client_upload_id())).thenReturn(
            Optional.of(new PhotoRepository.FinalizedPhoto(
                stored, 7L, request.client_upload_id(),
                PhotoFinalizeFingerprint.finalizeRequest(7L, request),
                "private", "pending", 91L, "Snapshot Place", "matched", "private"
            ))
        );
        PhotoRequests.Create changed = copy(
            request, request.caption(), 22.0, request.place_resolution_status(),
            request.expected_place_id(), request.visibility(), request.public_consent()
        );

        assertThatThrownBy(() -> service.create(7L, changed))
            .isInstanceOf(PhotoApiException.class)
            .satisfies(error -> {
                PhotoApiException typed = (PhotoApiException) error;
                assertThat(typed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(typed.code()).isEqualTo("PHOTO_FINALIZE_CONFLICT");
            });
        verify(photos, never()).createIfAbsent(eq(7L), any(), any(), any(), any(), any());
        verify(photos, never()).createNextGrant(anyLong(), anyLong());
    }

    @Test
    void changedPlaceWinnerReturnsTypedConflictBeforePhotoOrGrantWrite() {
        PhotoRequests.Create request = setLogRequest();
        uploaded(request);
        when(placeResolver.resolve(request.latitude(), request.longitude(), request.accuracy_m()))
            .thenReturn(new PhotoPlaceResolver.Resolution("matched", 92L, "Changed winner"));

        assertThatThrownBy(() -> service.create(7L, request))
            .isInstanceOf(PhotoApiException.class)
            .satisfies(error -> {
                PhotoApiException typed = (PhotoApiException) error;
                assertThat(typed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(typed.code()).isEqualTo("PHOTO_PLACE_MISMATCH");
            });
        verify(photos, never()).createIfAbsent(
            eq(7L), any(), any(), any(), any(), any()
        );
        verify(photos, never()).createNextGrant(anyLong(), anyLong());
    }

    @Test
    void resolverFailureLeavesPhotoAndGrantUnwritten() {
        PhotoRequests.Create request = setLogRequest();
        uploaded(request);
        when(placeResolver.resolve(request.latitude(), request.longitude(), request.accuracy_m()))
            .thenThrow(new PhotoApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PHOTO_PLACE_RESOLUTION_FAILED",
                "unavailable"
            ));

        assertThatThrownBy(() -> service.create(7L, request))
            .isInstanceOf(PhotoApiException.class)
            .extracting(error -> ((PhotoApiException) error).code())
            .isEqualTo("PHOTO_PLACE_RESOLUTION_FAILED");
        verify(photos, never()).createIfAbsent(
            eq(7L), any(), any(), any(), any(), any()
        );
        verify(photos, never()).createNextGrant(anyLong(), anyLong());
    }

    @Test
    void createContractRejectsClientCellId() {
        String json = """
            {"trip_id":7,"source":"camera",
             "client_upload_id":"11111111-1111-1111-1111-111111111111",
             "original_key":"original.jpg","thumb_key":"thumb.jpg",
             "original":{"content_type":"image/jpeg","size_bytes":1,"sha256":"%s"},
             "thumbnail":{"content_type":"image/jpeg","size_bytes":1,"sha256":"%s"},
             "cell_id":"8a2a1072b59ffff"}
            """.formatted("a".repeat(64), "b".repeat(64));

        assertThatThrownBy(() -> new ObjectMapper().readValue(json, PhotoRequests.Create.class))
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cell_id");
    }

    @Test
    void issuesDeterministicUploadPairOnlyAfterMembershipAuthorization() {
        PhotoRequests.Create request = request();

        PhotoResponses.UploadUrl urls = service.issueUploadUrl(7L, request.uploadRequest());

        verify(memberships).requireActiveMember(7L, 7L);
        assertThat(urls.original_object_key()).isEqualTo(request.original_key());
        assertThat(urls.thumbnail_object_key()).isEqualTo(request.thumb_key());
        assertThat(objects.signedPutObjectKeys()).containsExactly(
            request.original_key(),
            request.thumb_key()
        );
    }

    @Test
    void createsActiveMemberPhotoWithCalculatedCell() {
        PhotoRequests.Create request = request();
        long cellId = CellIdCalculator.fromCoords(35.815, 127.15);
        PhotoRepository.StoredPhoto stored = stored(cellId, "private");
        uploaded(request);
        when(photos.createIfAbsent(eq(7L), eq(request), eq(cellId), any(), org.mockito.ArgumentMatchers.isNull(), eq("private")))
            .thenReturn(Optional.of(stored));

        PhotoResponses.Detail response = service.create(7L, request);

        verify(memberships).requireActiveMember(7L, 7L);
        assertThat(response.cell_id()).isEqualTo(CellIdCalculator.toWire(cellId));
        assertThat(response.original_url()).isNull();
        assertThat(response.thumbnail_url()).isNull();
        assertThat(response.visibility()).isEqualTo("private");
    }

    @Test
    void publicTripPostDefaultsToPublicWithoutSeparateConsent() {
        PhotoRequests.Create request = request();
        long cellId = CellIdCalculator.fromCoords(35.815, 127.15);
        PhotoRepository.StoredPhoto stored = stored(cellId, "public");
        uploaded(request);
        when(photos.tripVisibility(request.trip_id())).thenReturn("public");
        when(photos.createIfAbsent(
            eq(7L),
            any(PhotoRequests.Create.class),
            eq(cellId),
            any(),
            isNull(),
            eq("public")
        )).thenReturn(Optional.of(stored));

        PhotoResponses.Detail response = service.create(7L, request);

        org.mockito.ArgumentCaptor<PhotoRequests.Create> effectiveRequest =
            org.mockito.ArgumentCaptor.forClass(PhotoRequests.Create.class);
        verify(photos).createIfAbsent(eq(7L), effectiveRequest.capture(), eq(cellId), any(), isNull(), eq("public"));
        assertThat(effectiveRequest.getValue().visibility()).isEqualTo("public");
        assertThat(effectiveRequest.getValue().public_consent()).isFalse();
        assertThat(response.visibility()).isEqualTo("public");
        assertThat(response.publication_status()).isEqualTo("public");
    }

    @Test
    void committedExactReplayReturnsPersistedResponseBeforeMembershipOrObjectChecks() {
        PhotoRequests.Create request = request();
        PhotoRepository.StoredPhoto stored = stored(
            CellIdCalculator.fromCoords(35.815, 127.15),
            "private"
        );
        String fingerprint = PhotoFinalizeFingerprint.finalizeRequest(7L, request);
        when(photos.findFinalized(7L, request.client_upload_id())).thenReturn(
            Optional.of(new PhotoRepository.FinalizedPhoto(
                stored,
                7L,
                request.client_upload_id(),
                fingerprint,
                "private",
                "pending",
                91L,
                "Original place snapshot",
                "matched",
                "trip_not_public"
            ))
        );

        PhotoResponses.Detail replay = service.create(7L, request);

        assertThat(replay.id()).isEqualTo(stored.id());
        assertThat(replay.original_url()).isNull();
        assertThat(replay.thumbnail_url()).isNull();
        assertThat(replay.place_id()).isEqualTo(91L);
        assertThat(replay.place_name()).isEqualTo("Original place snapshot");
        assertThat(replay.place_resolution_status()).isEqualTo("matched");
        assertThat(replay.publication_status()).isEqualTo("trip_not_public");
        org.mockito.Mockito.verifyNoInteractions(memberships);
        assertThat(objects.signedReadObjectKeys()).isEmpty();
    }

    @Test
    void duplicateFinalizeReturnsTheExistingPhotoAndChangedPayloadConflicts() {
        PhotoRequests.Create request = request();
        PhotoRepository.StoredPhoto stored = stored(
            CellIdCalculator.fromCoords(35.815, 127.15),
            "private"
        );
        String fingerprint = PhotoFinalizeFingerprint.finalizeRequest(7L, request);
        when(photos.findFinalized(7L, request.client_upload_id())).thenReturn(
            Optional.of(new PhotoRepository.FinalizedPhoto(
                stored,
                7L,
                request.client_upload_id(),
                fingerprint,
                "private",
                "pending"
            ))
        );

        PhotoResponses.Detail replay = service.create(7L, request);

        assertThat(replay.id()).isEqualTo(stored.id());
        org.mockito.Mockito.verify(photos, org.mockito.Mockito.never())
            .createIfAbsent(eq(7L), eq(request), any(), any(), any(), any());

        PhotoRequests.Create changed = new PhotoRequests.Create(
            request.trip_id(),
            request.source(),
            request.client_upload_id(),
            request.original_key(),
            request.thumb_key(),
            request.original(),
            request.thumbnail(),
            request.latitude(),
            request.longitude(),
            request.taken_at(),
            "changed caption"
        );
        assertThatThrownBy(() -> service.create(7L, changed))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);

        PhotoRequests.Create changedTrip = new PhotoRequests.Create(
            8L,
            request.source(),
            request.client_upload_id(),
            request.original_key(),
            request.thumb_key(),
            request.original(),
            request.thumbnail(),
            request.latitude(),
            request.longitude(),
            request.taken_at(),
            request.caption()
        );
        assertThatThrownBy(() -> service.create(7L, changedTrip))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsInvalidUploadedMetadataBeforeCreatingAnyPhoto() {
        PhotoRequests.Create request = request();
        objects.put(request.original_key(), "image/jpeg", "normalized-original");

        assertThatThrownBy(() -> service.create(7L, request))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);

        org.mockito.Mockito.verify(photos, org.mockito.Mockito.never())
            .createIfAbsent(eq(7L), eq(request), any(), any(), any(), any());
    }

    @Test
    void doesNotIssueReadsWhenEffectiveVisibilityDeniesTheActor() {
        when(visibility.canReadPhoto(8L, 11L)).thenReturn(false);

        assertThatThrownBy(() -> service.get(8L, 11L))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(objects.signedReadObjectKeys()).isEmpty();
    }

    @Test
    void changesVisibilityOnlyForThePhotoOwner() {
        PhotoRepository.StoredPhoto stored = stored(null, "private");
        PhotoRepository.StoredPhoto changed = stored(null, "group");
        uploaded(request());
        when(photos.lock(11L)).thenReturn(Optional.of(stored));
        when(photos.updateVisibility(11L, "group")).thenReturn(changed);

        PhotoResponses.Detail response = service.changeVisibility(
            7L,
            11L,
            new PhotoRequests.Visibility("group")
        );

        verify(photos).refreshCellProjection(null);
        assertThat(response.visibility()).isEqualTo("group");
    }

    @Test
    void rejectsMembersChangingSomeoneElsesPhoto() {
        PhotoRepository.StoredPhoto stored = new PhotoRepository.StoredPhoto(
            11L,
            7L,
            8L,
            "camera",
            null,
            null,
            null,
            TAKEN_AT,
            request().original_key(),
            request().thumb_key(),
            "caption",
            "private",
            "pending",
            TAKEN_AT
        );
        when(photos.lock(11L)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.changeVisibility(
            7L,
            11L,
            new PhotoRequests.Visibility("group")
        ))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private PhotoRequests.Create request() {
        String uploadId = "11111111-1111-1111-1111-111111111111";
        return new PhotoRequests.Create(
            7L,
            "camera",
            uploadId,
            "photos/accounts/7/trips/7/members/7/uploads/" + uploadId + "/original.jpg",
            "photos/accounts/7/trips/7/members/7/uploads/" + uploadId + "/thumbnail.jpg",
            new PhotoRequests.UploadObject("image/jpeg", 128L, "a".repeat(64)),
            new PhotoRequests.UploadObject("image/jpeg", 128L, "b".repeat(64)),
            35.815,
            127.15,
            TAKEN_AT,
            "caption"
        );
    }

    private void uploaded(PhotoRequests.Create request) {
        objects.put(
            request.original_key(),
            request.original_key(),
            "image/jpeg",
            request.original().size_bytes(),
            metadata(request, request.original(), "normalized-original")
        );
        objects.put(
            request.thumb_key(),
            request.thumb_key(),
            "image/jpeg",
            request.thumbnail().size_bytes(),
            metadata(request, request.thumbnail(), "thumbnail")
        );
    }

    private java.util.Map<String, String> metadata(
        PhotoRequests.Create request,
        PhotoRequests.UploadObject object,
        String derivative
    ) {
        return java.util.Map.of(
            "stog-account-id", "7",
            "stog-trip-id", request.trip_id().toString(),
            "stog-member-id", "7",
            "stog-upload-id", request.client_upload_id(),
            "stog-derivative", derivative,
            "stog-size", object.size_bytes().toString(),
            "stog-sha256", object.sha256()
        );
    }

    private PhotoRepository.StoredPhoto stored(Long cellId, String visibility) {
        PhotoRequests.Create request = request();
        return new PhotoRepository.StoredPhoto(
            11L,
            7L,
            7L,
            "camera",
            cellId,
            35.815,
            127.15,
            TAKEN_AT,
            request.original_key(),
            request.thumb_key(),
            "caption",
            visibility,
            "pending",
            TAKEN_AT
        );
    }
}
