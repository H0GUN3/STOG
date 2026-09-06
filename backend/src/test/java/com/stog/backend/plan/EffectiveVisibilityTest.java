package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class EffectiveVisibilityTest {
    @Autowired
    private EffectiveVisibilityService visibility;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void privateAndGroupReadsFailClosedForNonMembersAndRemovedMembers() {
        long ownerId = user("owner");
        long memberId = user("member");
        long outsiderId = user("outsider");
        long privateTripId = trip(ownerId, "private");
        long privatePhotoId = photo(privateTripId, ownerId, "private");
        long groupTripId = trip(ownerId, "group");
        long groupPhotoId = photo(groupTripId, ownerId, "group");
        long memberPrivatePhotoId = photo(groupTripId, memberId, "private");
        activeMember(groupTripId, memberId);

        assertThat(visibility.canReadTrip(null, privateTripId)).isFalse();
        assertThat(visibility.canReadPhoto(memberId, privatePhotoId)).isFalse();
        assertThat(visibility.canReadPhoto(ownerId, privatePhotoId)).isTrue();
        assertThat(visibility.canReadPhoto(999_999L, privatePhotoId)).isFalse();
        assertThat(visibility.canReadPhoto(memberId, groupPhotoId)).isTrue();
        assertThat(visibility.canReadPhoto(outsiderId, groupPhotoId)).isFalse();
        assertThat(visibility.canReadPhoto(ownerId, memberPrivatePhotoId)).isFalse();

        jdbc.sql("""
                UPDATE trip_members
                SET left_at = CURRENT_TIMESTAMP
                WHERE trip_id = :tripId AND user_id = :userId
                """)
            .param("tripId", groupTripId)
            .param("userId", memberId)
            .update();

        assertThat(visibility.canReadPhoto(memberId, groupPhotoId)).isFalse();
    }

    @Test
    void publicPhotoReadsRequireEveryEligibilityGateAndUseTheStricterScope() {
        long ownerId = user("owner");
        long memberId = user("member");
        long publicTripId = trip(ownerId, "public");
        long publicPhotoId = photo(publicTripId, ownerId, "public");
        activeMember(publicTripId, memberId);

        assertThat(EffectiveVisibility.stricter("private", "public"))
            .contains(EffectiveVisibility.Scope.PRIVATE);
        assertThat(EffectiveVisibility.stricter("public", "group"))
            .contains(EffectiveVisibility.Scope.GROUP);
        assertThat(EffectiveVisibility.stricter("public", "unknown")).isEmpty();
        assertThat(visibility.canReadTrip(null, publicTripId)).isTrue();
        assertThat(visibility.canReadPhoto(null, publicPhotoId)).isFalse();
        assertThat(visibility.canReadPhoto(memberId, publicPhotoId)).isTrue();

        jdbc.sql("UPDATE photos SET moderation_status = 'approved' WHERE id = :photoId")
            .param("photoId", publicPhotoId)
            .update();
        assertThat(visibility.canReadPhoto(null, publicPhotoId)).isFalse();

        jdbc.sql("""
                INSERT INTO photo_public_grants (photo_id, version, granted_by)
                VALUES (:photoId, 1, :ownerId)
                """)
            .param("photoId", publicPhotoId)
            .param("ownerId", ownerId)
            .update();

        assertThat(visibility.canReadPhoto(null, publicPhotoId)).isTrue();
    }

    private long user(String nickname) {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", nickname)
            .query(Long.class)
            .single();
    }

    private long trip(long ownerId, String visibility) {
        return jdbc.sql("""
                INSERT INTO trips (owner_id, title, activity_type, visibility, is_group)
                VALUES (:ownerId, :title, 'tour', :visibility, TRUE)
                RETURNING id
                """)
            .param("ownerId", ownerId)
            .param("title", visibility + " trip")
            .param("visibility", visibility)
            .query(Long.class)
            .single();
    }

    private long photo(long tripId, long userId, String visibility) {
        return jdbc.sql("""
                INSERT INTO photos (
                    trip_id, user_id, source, original_key, thumb_key, visibility
                )
                VALUES (
                    :tripId, :userId, 'camera', :originalKey, :thumbKey, :visibility
                )
                RETURNING id
                """)
            .param("tripId", tripId)
            .param("userId", userId)
            .param("originalKey", "task11/" + tripId + "-" + userId + "-original.jpg")
            .param("thumbKey", "task11/" + tripId + "-" + userId + "-thumb.jpg")
            .param("visibility", visibility)
            .query(Long.class)
            .single();
    }

    private void activeMember(long tripId, long userId) {
        jdbc.sql("INSERT INTO trip_members (trip_id, user_id) VALUES (:tripId, :userId)")
            .param("tripId", tripId)
            .param("userId", userId)
            .update();
    }
}
