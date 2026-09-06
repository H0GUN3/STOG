package com.stog.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FeedServiceTest {
    private static final Instant FEED_TIME = Instant.parse("2040-01-01T00:00:00Z");

    @Autowired
    private FeedService feed;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MockMvc mockMvc;

    private long sequence;

    @Test
    void returnsPublicPhotosWithoutApprovalOrGrant() {
        long visible = photo("visible", "public", "public", "approved", true, false, FEED_TIME);
        long privateTrip = photo("private-trip", "private", "public", "approved", true, false, FEED_TIME.plusSeconds(1));
        long groupTrip = photo("group-trip", "group", "public", "approved", true, false, FEED_TIME.plusSeconds(2));
        long pending = photo("pending", "public", "public", "pending", true, false, FEED_TIME.plusSeconds(3));
        long blocked = photo("blocked", "public", "public", "blocked", true, false, FEED_TIME.plusSeconds(4));
        long revoked = photo("revoked", "public", "public", "approved", true, true, FEED_TIME.plusSeconds(5));
        long privatePhoto = photo("private-photo", "public", "private", "approved", true, false, FEED_TIME.plusSeconds(6));
        long groupPhoto = photo("group-photo", "public", "group", "approved", true, false, FEED_TIME.plusSeconds(7));
        long noGrant = photo("no-grant", "public", "public", "approved", false, false, FEED_TIME.plusSeconds(8));
        long noFeedGrant = photo("no-feed-grant", "public", "public", "approved", true, false, FEED_TIME.plusSeconds(9));
        jdbc.sql("UPDATE photo_public_grants SET scope = ARRAY['signed_read'] WHERE photo_id = :photoId")
            .param("photoId", noFeedGrant)
            .update();
        long nonOwnerGrant = photo("non-owner-grant", "public", "public", "approved", true, false, FEED_TIME.plusSeconds(10));
        jdbc.sql("UPDATE photo_public_grants SET granted_by = :otherUserId WHERE photo_id = :photoId")
            .param("otherUserId", user("not-photo-owner"))
            .param("photoId", nonOwnerGrant)
            .update();

        List<Long> photoIds = feed.get(null, null, null).items().stream()
            .map(FeedResponses.Item::photo_id)
            .toList();

        assertThat(photoIds).contains(
            visible,
            pending,
            revoked,
            noGrant,
            noFeedGrant,
            nonOwnerGrant
        );
        assertThat(photoIds).doesNotContain(
            privateTrip,
            groupTrip,
            blocked,
            privatePhoto,
            groupPhoto
        );
    }

    @Test
    void exposesGuestFeedAndAuthenticatedLikeEndpoints() throws Exception {
        long viewerId = user("endpoint-viewer");
        long photoId = photo("endpoint", "public", "public", "approved", true, false, FEED_TIME);
        String ownerNickname = jdbc.sql("""
                SELECT u.nickname
                FROM photos p
                JOIN users u ON u.id = p.user_id
                WHERE p.id = :photoId
                """)
            .param("photoId", photoId)
            .query(String.class)
            .single();

        mockMvc.perform(get("/feed").param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].photo_id").value(photoId))
            .andExpect(jsonPath("$.items[0].owner_nickname").value(ownerNickname))
            .andExpect(jsonPath("$.items[0].thumbnail_media.state").value("unavailable"))
            .andExpect(jsonPath("$.items[0].liked_by_viewer").value(false));
        mockMvc.perform(post("/photos/{id}/like", photoId))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/photos/{id}/like", photoId)
                .with(jwt().jwt(token -> token.subject(Long.toString(viewerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.photo_id").value(photoId))
            .andExpect(jsonPath("$.like_count").value(1))
            .andExpect(jsonPath("$.liked_by_viewer").value(true));
        mockMvc.perform(post("/photos/{id}/like", photoId)
                .with(jwt().jwt(token -> token.subject(Long.toString(viewerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.like_count").value(1))
            .andExpect(jsonPath("$.liked_by_viewer").value(true));
        mockMvc.perform(delete("/photos/{id}/like", photoId)
                .with(jwt().jwt(token -> token.subject(Long.toString(viewerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.like_count").value(0))
            .andExpect(jsonPath("$.liked_by_viewer").value(false));
        mockMvc.perform(delete("/photos/{id}/like", photoId)
                .with(jwt().jwt(token -> token.subject(Long.toString(viewerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.like_count").value(0))
            .andExpect(jsonPath("$.liked_by_viewer").value(false));
    }

    @Test
    void exposesAuthenticatedSavedFeedAndSaveEndpoints() throws Exception {
        long savingViewerId = user("saved-endpoint-viewer");
        long otherViewerId = user("other-saved-endpoint-viewer");
        long photoId = photo("saved-endpoint", "public", "public", "approved", true, false, FEED_TIME);

        mockMvc.perform(get("/feed/saved"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/photos/{id}/save", photoId))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/photos/{id}/save", photoId)
                .with(jwt().jwt(token -> token.subject(Long.toString(savingViewerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.photo_id").value(photoId))
            .andExpect(jsonPath("$.saved_by_viewer").value(true));
        mockMvc.perform(post("/photos/{id}/save", photoId)
                .with(jwt().jwt(token -> token.subject(Long.toString(savingViewerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.saved_by_viewer").value(true));
        mockMvc.perform(get("/feed")
                .with(jwt().jwt(token -> token.subject(Long.toString(savingViewerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].saved_by_viewer").value(true));
        mockMvc.perform(get("/feed/saved")
                .with(jwt().jwt(token -> token.subject(Long.toString(savingViewerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].photo_id").value(photoId))
            .andExpect(jsonPath("$.items[0].saved_by_viewer").value(true));
        mockMvc.perform(get("/feed/saved")
                .with(jwt().jwt(token -> token.subject(Long.toString(otherViewerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(delete("/photos/{id}/save", photoId)
                .with(jwt().jwt(token -> token.subject(Long.toString(savingViewerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.saved_by_viewer").value(false));
        mockMvc.perform(delete("/photos/{id}/save", photoId)
                .with(jwt().jwt(token -> token.subject(Long.toString(savingViewerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.saved_by_viewer").value(false));
    }

    @Test
    void overlaysLikedStateForTheViewerWithoutChangingPublicEligibility() {
        long viewerId = user("viewer");
        long photoId = photo("liked", "public", "public", "approved", true, false, FEED_TIME);
        jdbc.sql("""
                INSERT INTO likes (user_id, target_type, target_id)
                VALUES (:userId, 'photo', :photoId)
                """)
            .param("userId", viewerId)
            .param("photoId", photoId)
            .update();
        jdbc.sql("UPDATE photos SET like_count = 1 WHERE id = :photoId")
            .param("photoId", photoId)
            .update();

        FeedResponses.Item viewerItem = item(feed.get(viewerId, null, null), photoId);
        FeedResponses.Item guestItem = item(feed.get(null, null, null), photoId);

        assertThat(viewerItem.liked_by_viewer()).isTrue();
        assertThat(guestItem.liked_by_viewer()).isFalse();
        assertThat(viewerItem.like_count()).isEqualTo(1L);
        assertThat(viewerItem.thumbnail_key()).isEqualTo("task13/liked-thumb.jpg");
        assertThat(viewerItem.thumbnail_media())
            .isEqualTo(FeedResponses.ThumbnailMedia.unavailable());
    }

    @Test
    void keepsPublicPhotoVisibleWhenGrantScopeIsFeedOnly() {
        long photoId = photo(
            "feed-only-grant", "public", "public", "approved", true, false, FEED_TIME
        );
        jdbc.sql("UPDATE photo_public_grants SET scope = ARRAY['feed'] WHERE photo_id = :photoId")
            .param("photoId", photoId)
            .update();

        FeedResponses.Item item = item(feed.get(null, null, null), photoId);

        assertThat(item.thumbnail_key()).isEqualTo("task13/feed-only-grant-thumb.jpg");
    }

    @Test
    void paginatesInStableCreatedAtThenPhotoIdOrderWithoutDuplicateOrSkip() {
        long first = photo("first", "public", "public", "approved", true, false, FEED_TIME);
        long second = photo("second", "public", "public", "approved", true, false, FEED_TIME);
        long third = photo("third", "public", "public", "approved", true, false, FEED_TIME.plusSeconds(1));
        long fourth = photo("fourth", "public", "public", "approved", true, false, FEED_TIME.plusSeconds(1));

        FeedResponses.Page firstPage = feed.get(null, null, 2);
        FeedResponses.Page secondPage = feed.get(null, firstPage.next_cursor(), 2);
        List<Long> returned = new ArrayList<>();
        returned.addAll(firstPage.items().stream().map(FeedResponses.Item::photo_id).toList());
        returned.addAll(secondPage.items().stream().map(FeedResponses.Item::photo_id).toList());

        assertThat(firstPage.next_cursor()).isNotBlank();
        assertThat(secondPage.next_cursor()).isNull();
        assertThat(returned).containsExactly(fourth, third, second, first);
        assertThat(returned).doesNotHaveDuplicates();
    }

    @Test
    void rejectsMalformedCursorsAndLimitsBeforeRunningTheFeed() {
        assertStatus(() -> feed.get(null, "not-a-valid-cursor", 1), HttpStatus.BAD_REQUEST);
        assertStatus(() -> feed.get(null, null, 21), HttpStatus.BAD_REQUEST);
    }

    private FeedResponses.Item item(FeedResponses.Page page, long photoId) {
        return page.items().stream()
            .filter(item -> item.photo_id() == photoId)
            .findFirst()
            .orElseThrow();
    }

    private long photo(
        String name,
        String tripVisibility,
        String photoVisibility,
        String moderationStatus,
        boolean activeGrant,
        boolean revokeGrant,
        Instant createdAt
    ) {
        long ownerId = user("owner-" + name);
        long tripId = jdbc.sql("""
                INSERT INTO trips (owner_id, title, activity_type, visibility)
                VALUES (:ownerId, :title, 'tour', :visibility)
                RETURNING id
                """)
            .param("ownerId", ownerId)
            .param("title", "task13 feed " + name)
            .param("visibility", tripVisibility)
            .query(Long.class)
            .single();
        long photoId = jdbc.sql("""
                INSERT INTO photos (
                    trip_id, user_id, source, original_key, thumb_key, caption,
                    visibility, moderation_status, created_at
                )
                VALUES (
                    :tripId, :ownerId, 'camera', :originalKey, :thumbKey, :caption,
                    :visibility, :moderationStatus, :createdAt
                )
                RETURNING id
                """)
            .param("tripId", tripId)
            .param("ownerId", ownerId)
            .param("originalKey", "task13/" + name + ".jpg")
            .param("thumbKey", "task13/" + name + "-thumb.jpg")
            .param("caption", "task13 " + name)
            .param("visibility", photoVisibility)
            .param("moderationStatus", moderationStatus)
            .param("createdAt", Timestamp.from(createdAt))
            .query(Long.class)
            .single();
        if (activeGrant) {
            jdbc.sql("""
                    INSERT INTO photo_public_grants (photo_id, version, granted_by)
                    VALUES (:photoId, 1, :ownerId)
                    """)
                .param("photoId", photoId)
                .param("ownerId", ownerId)
                .update();
            if (revokeGrant) {
                jdbc.sql("""
                        UPDATE photo_public_grants
                        SET revoked_at = CURRENT_TIMESTAMP
                        WHERE photo_id = :photoId
                        """)
                    .param("photoId", photoId)
                    .update();
            }
        }
        return photoId;
    }

    private long user(String name) {
        sequence++;
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", "task13-feed-" + name + "-" + sequence)
            .query(Long.class)
            .single();
    }

    private void assertStatus(Runnable action, HttpStatus expected) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(expected);
    }
}
