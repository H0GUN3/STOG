package com.stog.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
class SavedPhotoServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2042-01-01T00:00:00Z");

    @Autowired
    private SavedPhotoService savedPhotos;

    @Autowired
    private FeedService feed;

    @Autowired
    private JdbcClient jdbc;

    private long sequence;

    @Test
    void savesAndUnsavesIdempotentlyWithoutLeakingAnotherViewersFeed() {
        long ownerId = user("owner");
        long savingViewerId = user("saving-viewer");
        long otherViewerId = user("other-viewer");
        long photoId = photo(ownerId, "eligible", "public", "approved", true);

        assertThat(savedPhotos.save(savingViewerId, photoId))
            .isEqualTo(new SavedPhotoResponses.State(photoId, true));
        assertThat(savedPhotos.save(savingViewerId, photoId))
            .isEqualTo(new SavedPhotoResponses.State(photoId, true));
        assertThat(savedRows(savingViewerId, photoId)).isEqualTo(1L);
        assertThat(feed.get(savingViewerId, null, null).items())
            .filteredOn(item -> item.photo_id() == photoId)
            .singleElement()
            .extracting(FeedResponses.Item::saved_by_viewer)
            .isEqualTo(true);
        assertThat(feed.getSaved(savingViewerId, null, null).items())
            .extracting(FeedResponses.Item::photo_id)
            .containsExactly(photoId);
        assertThat(feed.getSaved(otherViewerId, null, null).items()).isEmpty();

        assertThat(savedPhotos.unsave(savingViewerId, photoId))
            .isEqualTo(new SavedPhotoResponses.State(photoId, false));
        assertThat(savedPhotos.unsave(savingViewerId, photoId))
            .isEqualTo(new SavedPhotoResponses.State(photoId, false));
        assertThat(savedRows(savingViewerId, photoId)).isZero();
    }

    @Test
    void rejectsPrivatePhotosButAcceptsPublicPhotosWithoutFeedGrantScope() {
        long ownerId = user("owner");
        long viewerId = user("viewer");
        long privatePhotoId = photo(ownerId, "private", "private", "approved", true);
        long noFeedGrantPhotoId = photo(ownerId, "no-feed-grant", "public", "approved", true);
        jdbc.sql("UPDATE photo_public_grants SET scope = ARRAY['like'] WHERE photo_id = :photoId")
            .param("photoId", noFeedGrantPhotoId)
            .update();

        assertNotFound(() -> savedPhotos.save(viewerId, privatePhotoId));
        assertThat(savedPhotos.save(viewerId, noFeedGrantPhotoId))
            .isEqualTo(new SavedPhotoResponses.State(noFeedGrantPhotoId, true));
        assertThat(savedRows(viewerId, privatePhotoId)).isZero();
        assertThat(savedRows(viewerId, noFeedGrantPhotoId)).isEqualTo(1L);
    }

    private long photo(
        long ownerId,
        String name,
        String visibility,
        String moderationStatus,
        boolean grant
    ) {
        long tripId = jdbc.sql("""
                INSERT INTO trips (owner_id, title, activity_type, visibility)
                VALUES (:ownerId, :title, 'tour', 'public')
                RETURNING id
                """)
            .param("ownerId", ownerId)
            .param("title", "saved photo " + name)
            .query(Long.class)
            .single();
        long photoId = jdbc.sql("""
                INSERT INTO photos (
                    trip_id, user_id, source, original_key, thumb_key, visibility,
                    moderation_status, created_at
                )
                VALUES (
                    :tripId, :ownerId, 'camera', :originalKey, :thumbKey, :visibility,
                    :moderationStatus, :createdAt
                )
                RETURNING id
                """)
            .param("tripId", tripId)
            .param("ownerId", ownerId)
            .param("originalKey", "saved/" + name + ".jpg")
            .param("thumbKey", "saved/" + name + "-thumb.jpg")
            .param("visibility", visibility)
            .param("moderationStatus", moderationStatus)
            .param("createdAt", Timestamp.from(CREATED_AT))
            .query(Long.class)
            .single();
        if (grant) {
            jdbc.sql("""
                    INSERT INTO photo_public_grants (photo_id, version, granted_by)
                    VALUES (:photoId, 1, :ownerId)
                    """)
                .param("photoId", photoId)
                .param("ownerId", ownerId)
                .update();
        }
        return photoId;
    }

    private long user(String name) {
        sequence++;
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", "saved-service-" + name + "-" + sequence)
            .query(Long.class)
            .single();
    }

    private long savedRows(long userId, long photoId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM saved_photos
                WHERE user_id = :userId AND photo_id = :photoId
                """)
            .param("userId", userId)
            .param("photoId", photoId)
            .query(Long.class)
            .single();
    }

    private void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
