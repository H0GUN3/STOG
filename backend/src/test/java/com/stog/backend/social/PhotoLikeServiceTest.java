package com.stog.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stog.backend.cell.CellIdCalculator;
import com.stog.backend.storage.PhotoRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class PhotoLikeServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2041-01-01T00:00:00Z");
    private static final long CELL_ID = CellIdCalculator.fromCoords(35.815, 127.15);

    @Autowired
    private PhotoLikeService likes;

    @Autowired
    private FeedService feed;

    @Autowired
    private PhotoRepository photos;

    @Autowired
    private JdbcClient jdbc;

    private final String fixturePrefix = "task13-like-" + UUID.randomUUID();
    private long sequence;

    @AfterEach
    void cleanupCommittedFixtures() {
        jdbc.sql("""
                DELETE FROM likes
                WHERE user_id IN (
                    SELECT id FROM users WHERE nickname LIKE :prefix
                )
                   OR (
                       target_type = 'photo'
                       AND target_id IN (
                           SELECT p.id
                           FROM photos p
                           JOIN users owner ON owner.id = p.user_id
                           WHERE owner.nickname LIKE :prefix
                       )
                   )
                """)
            .param("prefix", fixturePrefix + "%")
            .update();
        jdbc.sql("""
                DELETE FROM cell_stats
                WHERE cell_id IN (
                    SELECT DISTINCT p.cell_id
                    FROM photos p
                    JOIN users owner ON owner.id = p.user_id
                    WHERE owner.nickname LIKE :prefix
                      AND p.cell_id IS NOT NULL
                )
                  AND landmark_count = 0
                """)
            .param("prefix", fixturePrefix + "%")
            .update();
        jdbc.sql("""
                DELETE FROM photos
                WHERE user_id IN (
                    SELECT id FROM users WHERE nickname LIKE :prefix
                )
                """)
            .param("prefix", fixturePrefix + "%")
            .update();
        jdbc.sql("""
                DELETE FROM trips
                WHERE owner_id IN (
                    SELECT id FROM users WHERE nickname LIKE :prefix
                )
                """)
            .param("prefix", fixturePrefix + "%")
            .update();
        jdbc.sql("DELETE FROM users WHERE nickname LIKE :prefix")
            .param("prefix", fixturePrefix + "%")
            .update();
    }

    @Test
    @Transactional
    void makesDuplicateLikeAndDeleteRequestsIdempotent() {
        long ownerId = user("owner");
        long viewerId = user("viewer");
        long photoId = eligiblePhoto(ownerId, "single", CELL_ID);

        PhotoLikeResponses.State liked = likes.like(viewerId, photoId);

        assertThat(liked).isEqualTo(new PhotoLikeResponses.State(photoId, 1L, true));
        assertThat(likes.like(viewerId, photoId))
            .isEqualTo(new PhotoLikeResponses.State(photoId, 1L, true));
        assertThat(feed.get(viewerId, null, 20).items())
            .filteredOn(item -> item.photo_id() == photoId)
            .singleElement()
            .satisfies(item -> {
                assertThat(item.liked_by_viewer()).isTrue();
                assertThat(item.like_count()).isEqualTo(1L);
            });
        assertLikeCounts(photoId, 1L);
        assertProjection(CELL_ID, 1L, photoId);

        assertThat(likes.unlike(viewerId, photoId))
            .isEqualTo(new PhotoLikeResponses.State(photoId, 0L, false));
        assertThat(likes.unlike(viewerId, photoId))
            .isEqualTo(new PhotoLikeResponses.State(photoId, 0L, false));
        assertLikeCounts(photoId, 0L);
        assertProjection(CELL_ID, 0L, photoId);
    }

    @Test
    @Transactional
    void recomputesTheEarliestPhotoTieAndDropsBlockedPhotosFromTheTop() {
        long ownerId = user("owner");
        long firstViewerId = user("first-viewer");
        long secondViewerId = user("second-viewer");
        long firstPhotoId = eligiblePhoto(ownerId, "first", CELL_ID);
        long secondPhotoId = eligiblePhoto(ownerId, "second", CELL_ID);

        likes.like(firstViewerId, firstPhotoId);
        likes.like(secondViewerId, secondPhotoId);

        assertProjection(CELL_ID, 2L, firstPhotoId);
        jdbc.sql("""
                UPDATE photo_public_grants
                SET revoked_at = CURRENT_TIMESTAMP
                WHERE photo_id = :photoId
                """)
            .param("photoId", firstPhotoId)
            .update();
        photos.refreshCellProjection(CELL_ID);
        assertProjection(CELL_ID, 2L, firstPhotoId);

        jdbc.sql("UPDATE photos SET moderation_status = 'blocked' WHERE id = :photoId")
            .param("photoId", secondPhotoId)
            .update();
        photos.refreshCellProjection(CELL_ID);
        assertProjection(CELL_ID, 1L, firstPhotoId);
    }

    @Test
    void rollsBackTheLikeDeleteWhenCachedCountMutationFails() {
        long ownerId = user("owner");
        long viewerId = user("viewer");
        long photoId = eligiblePhoto(ownerId, "rollback", CELL_ID);
        jdbc.sql("""
                INSERT INTO likes (user_id, target_type, target_id)
                VALUES (:userId, 'photo', :photoId)
                """)
            .param("userId", viewerId)
            .param("photoId", photoId)
            .update();

        assertThatThrownBy(() -> likes.unlike(viewerId, photoId)).isInstanceOf(RuntimeException.class);

        assertThat(photoLikeRows(photoId)).isEqualTo(1L);
        assertThat(photoLikeCount(photoId)).isZero();
    }

    @Test
    void concurrentUnlikesSerializeAndLeaveNoCountDrift() throws Exception {
        long ownerId = user("owner");
        long viewerId = user("viewer");
        long photoId = eligiblePhoto(ownerId, "concurrent-unlike", CELL_ID);
        likes.like(viewerId, photoId);

        List<PhotoLikeResponses.State> states = concurrently(
            () -> likes.unlike(viewerId, photoId),
            () -> likes.unlike(viewerId, photoId)
        );

        assertThat(states).containsOnly(new PhotoLikeResponses.State(photoId, 0L, false));
        assertLikeCounts(photoId, 0L);
        assertProjection(CELL_ID, 0L, photoId);
    }

    @Test
    void concurrentDuplicateLikesReturnOneStableStateWithoutCountDrift() throws Exception {
        long ownerId = user("owner");
        long viewerId = user("viewer");
        long photoId = eligiblePhoto(ownerId, "concurrent-duplicate", CELL_ID);

        List<PhotoLikeResponses.State> states = concurrently(
            () -> likes.like(viewerId, photoId),
            () -> likes.like(viewerId, photoId)
        );

        assertThat(states).containsOnly(new PhotoLikeResponses.State(photoId, 1L, true));
        assertLikeCounts(photoId, 1L);
        assertProjection(CELL_ID, 1L, photoId);
    }

    @Test
    void concurrentLikesOnDifferentPhotosInOneCellKeepProjectionCountsAndTieOrder() throws Exception {
        long ownerId = user("owner");
        long firstViewerId = user("first-viewer");
        long secondViewerId = user("second-viewer");
        long firstPhotoId = eligiblePhoto(ownerId, "concurrent-first", CELL_ID);
        long secondPhotoId = eligiblePhoto(ownerId, "concurrent-second", CELL_ID);

        concurrently(
            () -> likes.like(firstViewerId, firstPhotoId),
            () -> likes.like(secondViewerId, secondPhotoId)
        );

        assertLikeCounts(firstPhotoId, 1L);
        assertLikeCounts(secondPhotoId, 1L);
        assertProjection(CELL_ID, 2L, firstPhotoId);
        assertThat(jdbc.sql("""
                SELECT COUNT(*)
                FROM likes
                WHERE target_type = 'photo'
                  AND target_id IN (:firstPhotoId, :secondPhotoId)
                """)
            .param("firstPhotoId", firstPhotoId)
            .param("secondPhotoId", secondPhotoId)
            .query(Long.class)
            .single()).isEqualTo(2L);
    }

    @SafeVarargs
    private final List<PhotoLikeResponses.State> concurrently(
        Callable<PhotoLikeResponses.State>... actions
    ) throws Exception {
        CyclicBarrier ready = new CyclicBarrier(actions.length);
        ExecutorService executor = Executors.newFixedThreadPool(actions.length);
        try {
            List<Future<PhotoLikeResponses.State>> futures = java.util.Arrays.stream(actions)
                .map(action -> executor.submit(() -> {
                    ready.await(5, TimeUnit.SECONDS);
                    return action.call();
                }))
                .toList();
            List<PhotoLikeResponses.State> results = new java.util.ArrayList<>();
            for (Future<PhotoLikeResponses.State> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private long eligiblePhoto(long ownerId, String name, long cellId) {
        long tripId = jdbc.sql("""
                INSERT INTO trips (owner_id, title, activity_type, visibility)
                VALUES (:ownerId, :title, 'tour', 'public')
                RETURNING id
                """)
            .param("ownerId", ownerId)
            .param("title", fixturePrefix + " trip " + name)
            .query(Long.class)
            .single();
        long photoId = jdbc.sql("""
                INSERT INTO photos (
                    trip_id, user_id, source, cell_id, lat, lng, taken_at,
                    original_key, thumb_key, caption, visibility, moderation_status,
                    created_at
                )
                VALUES (
                    :tripId, :ownerId, 'camera', :cellId, 35.815, 127.15, :takenAt,
                    :originalKey, :thumbKey, :caption, 'public', 'approved', :createdAt
                )
                RETURNING id
                """)
            .param("tripId", tripId)
            .param("ownerId", ownerId)
            .param("cellId", cellId)
            .param("takenAt", Timestamp.from(CREATED_AT))
            .param("originalKey", fixturePrefix + "/" + name + ".jpg")
            .param("thumbKey", fixturePrefix + "/" + name + "-thumb.jpg")
            .param("caption", "task13 " + name)
            .param("createdAt", Timestamp.from(CREATED_AT))
            .query(Long.class)
            .single();
        jdbc.sql("""
                INSERT INTO photo_public_grants (photo_id, version, granted_by)
                VALUES (:photoId, 1, :ownerId)
                """)
            .param("photoId", photoId)
            .param("ownerId", ownerId)
            .update();
        return photoId;
    }

    private long user(String name) {
        sequence++;
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", fixturePrefix + "-" + name + "-" + sequence)
            .query(Long.class)
            .single();
    }

    private void assertLikeCounts(long photoId, long expected) {
        assertThat(photoLikeRows(photoId)).isEqualTo(expected);
        assertThat(photoLikeCount(photoId)).isEqualTo(expected);
    }

    private long photoLikeRows(long photoId) {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM likes
                WHERE target_type = 'photo'
                  AND target_id = :photoId
                """)
            .param("photoId", photoId)
            .query(Long.class)
            .single();
    }

    private long photoLikeCount(long photoId) {
        return jdbc.sql("SELECT like_count FROM photos WHERE id = :photoId")
            .param("photoId", photoId)
            .query(Long.class)
            .single();
    }

    private void assertProjection(long cellId, long expectedLikes, long expectedTopPhotoId) {
        assertThat(jdbc.sql("""
                SELECT public_photo_count || '|' || public_photo_like_count || '|' || top_photo_id
                FROM cell_stats
                WHERE cell_id = :cellId
                """)
            .param("cellId", cellId)
            .query(String.class)
            .single()).isEqualTo(
                "" + expectedPhotoCount(cellId) + "|" + expectedLikes + "|" + expectedTopPhotoId
            );
    }

    private long expectedPhotoCount(long cellId) {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM photos p
                JOIN trips t ON t.id = p.trip_id
                WHERE p.cell_id = :cellId
                   AND t.visibility = 'public'
                   AND p.visibility = 'public'
                   AND p.moderation_status <> 'blocked'
                """)
            .param("cellId", cellId)
            .query(Long.class)
            .single();
    }

}
