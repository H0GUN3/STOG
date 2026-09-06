package com.stog.backend.profile;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stog.backend.cell.CellIdCalculator;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProfileSummaryEndpointsTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void summaryUnionsOnlyOwnedEligibleCellsAndKeepsOtherUsersOut() throws Exception {
        long userId = user("summary-owner", 73);
        long otherUserId = user("summary-other", 99);
        long ownedTripId = trip(userId, "owned");
        long sharedTripId = trip(otherUserId, "shared");
        trip(otherUserId, "other");
        jdbc.sql("INSERT INTO trip_members (trip_id, user_id) VALUES (:tripId, :userId)")
            .param("tripId", sharedTripId)
            .param("userId", userId)
            .update();

        long visitCell = CellIdCalculator.fromCoords(35.815, 127.15);
        long photoCell = CellIdCalculator.fromCoords(35.816, 127.151);
        long blockedPhotoCell = CellIdCalculator.fromCoords(35.818, 127.153);
        long otherCell = CellIdCalculator.fromCoords(35.819, 127.154);
        visit(userId, ownedTripId, visitCell, "visited", false, "a");
        visit(userId, ownedTripId, visitCell, "passed", false, "b");
        visit(userId, ownedTripId, otherCell, "passed", true, "c");
        visit(otherUserId, ownedTripId, otherCell, "visited", false, "d");
        photo(userId, ownedTripId, photoCell, 35.816, 127.151, "pending", "eligible");
        photo(userId, ownedTripId, null, null, null, "pending", "coordinate-less");
        photo(userId, ownedTripId, blockedPhotoCell, 35.818, 127.153, "blocked", "blocked");
        photo(otherUserId, ownedTripId, otherCell, 35.819, 127.154, "pending", "other");

        mockMvc.perform(get("/profile/summary")
                .with(jwt().jwt(token -> token.subject(Long.toString(userId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user_id").value(userId))
            .andExpect(jsonPath("$.nickname").value("summary-owner"))
            .andExpect(jsonPath("$.profile_image_url").isEmpty())
            .andExpect(jsonPath("$.trip_count").value(2))
            .andExpect(jsonPath("$.visited_cell_count").value(2))
            .andExpect(jsonPath("$.photo_count").value(3))
            .andExpect(jsonPath("$.honey_balance").value(73));

        mockMvc.perform(get("/trips/me/home")
                .with(jwt().jwt(token -> token.subject(Long.toString(userId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.visited_cell_count").value(2));
    }

    private long user(String nickname, long honeyBalance) {
        return jdbc.sql("""
                INSERT INTO users (nickname, honey_balance)
                VALUES (:nickname, :honeyBalance)
                RETURNING id
                """)
            .param("nickname", nickname)
            .param("honeyBalance", honeyBalance)
            .query(Long.class)
            .single();
    }

    private long trip(long ownerId, String title) {
        return jdbc.sql("""
                INSERT INTO trips (owner_id, title, activity_type)
                VALUES (:ownerId, :title, 'tour')
                RETURNING id
                """)
            .param("ownerId", ownerId)
            .param("title", title)
            .query(Long.class)
            .single();
    }

    private void visit(
        long userId,
        long tripId,
        long cellId,
        String status,
        boolean interpolated,
        String fingerprintCharacter
    ) {
        jdbc.sql("""
                INSERT INTO visits (
                    user_id, trip_id, cell_id, lat, lng, entered_at, left_at,
                    status, is_interpolated, client_visit_id, payload_fingerprint
                )
                VALUES (
                    :userId, :tripId, :cellId, 35.815, 127.15, :enteredAt, :leftAt,
                    :status, :interpolated, :clientVisitId, :fingerprint
                )
                """)
            .param("userId", userId)
            .param("tripId", tripId)
            .param("cellId", cellId)
            .param("enteredAt", java.sql.Timestamp.from(Instant.parse("2026-08-26T10:00:00Z")))
            .param("leftAt", java.sql.Timestamp.from(Instant.parse("2026-08-26T10:30:00Z")))
            .param("status", status)
            .param("interpolated", interpolated)
            .param("clientVisitId", UUID.randomUUID())
            .param("fingerprint", fingerprintCharacter.repeat(64))
            .update();
    }

    private void photo(
        long userId,
        long tripId,
        Long cellId,
        Double latitude,
        Double longitude,
        String moderationStatus,
        String suffix
    ) {
        jdbc.sql("""
                INSERT INTO photos (
                    trip_id, user_id, source, cell_id, lat, lng, original_key, thumb_key,
                    moderation_status
                )
                VALUES (
                    :tripId, :userId, 'camera', :cellId, :latitude, :longitude,
                    :originalKey, :thumbKey, :moderationStatus
                )
                """)
            .param("tripId", tripId)
            .param("userId", userId)
            .param("cellId", cellId)
            .param("latitude", latitude)
            .param("longitude", longitude)
            .param("originalKey", "profile-summary/" + suffix + "/original.jpg")
            .param("thumbKey", "profile-summary/" + suffix + "/thumb.jpg")
            .param("moderationStatus", moderationStatus)
            .update();
    }
}
