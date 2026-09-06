package com.stog.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stog.backend.storage.GcsReadUrlSigner;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class FeedThumbnailMediaResolverTest {
    private static final String THUMBNAIL_KEY =
        "photos/42/11111111-1111-1111-1111-111111111111-thumb.jpg";
    private static final String PROFILE_IMAGE_KEY = "profiles/42/avatar.jpg";

    @Mock
    private ObjectProvider<GcsReadUrlSigner> provider;

    @Test
    void reportsUnavailableWithoutSignedReadEligibilityOrMediaSurface() {
        FeedThumbnailMediaResolver resolver = new FeedThumbnailMediaResolver(provider);

        assertThat(resolver.resolve(false, THUMBNAIL_KEY))
            .isEqualTo(FeedResponses.ThumbnailMedia.unavailable());
        assertThat(resolver.resolve(true, THUMBNAIL_KEY))
            .isEqualTo(FeedResponses.ThumbnailMedia.unavailable());
    }

    @Test
    void returnsOnlyTheShortLivedSignedUrlFromTheMediaSurface() {
        GcsReadUrlSigner signedReads = mock(GcsReadUrlSigner.class);
        when(provider.getIfAvailable()).thenReturn(signedReads);
        when(signedReads.issueReadUrl(THUMBNAIL_KEY)).thenReturn(
            URI.create("https://storage.test/signed-thumbnail")
        );

        FeedResponses.ThumbnailMedia media =
            new FeedThumbnailMediaResolver(provider).resolve(true, THUMBNAIL_KEY);

        assertThat(media.state()).isEqualTo("available");
        assertThat(media.signed_url()).hasToString("https://storage.test/signed-thumbnail");
    }

    @Test
    void reportsFailedWhenTheMediaSurfaceCannotValidateOrSignTheObject() {
        GcsReadUrlSigner signedReads = mock(GcsReadUrlSigner.class);
        when(provider.getIfAvailable()).thenReturn(signedReads);
        when(signedReads.issueReadUrl(THUMBNAIL_KEY)).thenThrow(
            new IllegalArgumentException("metadata unavailable")
        );

        assertThat(new FeedThumbnailMediaResolver(provider).resolve(true, THUMBNAIL_KEY))
            .isEqualTo(FeedResponses.ThumbnailMedia.failed());
    }

    @Test
    void signsAnOwnerProfileImageWhenAProfileKeyExists() {
        GcsReadUrlSigner signedReads = mock(GcsReadUrlSigner.class);
        when(provider.getIfAvailable()).thenReturn(signedReads);
        when(signedReads.issueReadUrl(PROFILE_IMAGE_KEY)).thenReturn(
            URI.create("https://storage.test/signed-profile")
        );

        assertThat(new FeedThumbnailMediaResolver(provider)
            .resolveProfileImage(PROFILE_IMAGE_KEY))
            .hasToString("https://storage.test/signed-profile");
    }
}
