package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
class TripMembershipServiceTest {
    @Autowired
    private TripMembershipService memberships;

    @Autowired
    private PlanningService planning;

    @Autowired
    private ItineraryService itineraries;

    @Autowired
    private TripCollectionStateService collectionStates;

    @Autowired
    private TripLifecycleService lifecycle;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void ownerInviteLetsAnActiveMemberEditTheSharedPlanAndReadChanges() {
        long ownerId = user("owner");
        long memberId = user("member");
        long tripId = trip(ownerId, "shared");

        TripMembershipResponses.InviteLink invite = memberships.createInvite(ownerId, tripId);
        TripMembershipResponses.Joined joined = memberships.join(memberId, invite.token());
        BasketResponses.LinkAdded basket = planning.addLink(memberId, new BasketRequests.AddLink(
            tripId,
            "naver",
            "https://example.test/member",
            "member place",
            null
        ));
        itineraries.replace(memberId, tripId, new ItineraryRequests.Replace(List.of(
            new ItineraryRequests.Item(basket.id(), 1, 0, "09:30", 30)
        )));

        assertThat(joined.trip_id()).isEqualTo(tripId);
        assertThat(invite.join_path()).isEqualTo("/trip-invites/" + invite.token() + "/join");
        assertThat(jdbc.sql("SELECT is_group FROM trips WHERE id = :tripId")
            .param("tripId", tripId)
            .query(Boolean.class)
            .single()).isTrue();
        assertThat(jdbc.sql("SELECT token_hash FROM trip_invites WHERE trip_id = :tripId")
            .param("tripId", tripId)
            .query(String.class)
            .single()).isNotEqualTo(invite.token());
        assertThat(jdbc.sql("SELECT added_by FROM basket_items WHERE id = :id")
            .param("id", basket.id())
            .query(Long.class)
            .single()).isEqualTo(memberId);
        assertThat(itineraries.changes(memberId, tripId).changes())
            .singleElement()
            .satisfies(change -> {
                assertThat(change.user_id()).isEqualTo(memberId);
                assertThat(change.action()).isEqualTo("replace");
            });
    }

    @Test
    void activeMembersCanDiscoverOwnerAndParticipantsButRemovedMembersCannot() {
        long ownerId = user("owner-list");
        long memberId = user("member-list");
        long outsiderId = user("outsider-list");
        long tripId = trip(ownerId, "member-listing");
        TripMembershipResponses.InviteLink invite = memberships.createInvite(ownerId, tripId);
        memberships.join(memberId, invite.token());

        TripMembershipResponses.Members visible = memberships.members(memberId, tripId);

        assertThat(visible.viewer_id()).isEqualTo(memberId);
        assertThat(visible.owner_id()).isEqualTo(ownerId);
        assertThat(visible.members())
            .extracting(TripMembershipResponses.Member::user_id)
            .containsExactlyInAnyOrder(ownerId, memberId);
        assertThat(visible.members())
            .extracting(TripMembershipResponses.Member::nickname)
            .containsExactlyInAnyOrder("owner-list", "member-list");
        assertThat(visible.members()).allSatisfy(member -> assertThat(member.joined_at()).isNotNull());
        assertDenied(() -> memberships.members(outsiderId, tripId));

        memberships.remove(ownerId, tripId, memberId);
        assertDenied(() -> memberships.members(memberId, tripId));
        assertThat(memberships.members(ownerId, tripId).members())
            .extracting(TripMembershipResponses.Member::user_id)
            .containsExactly(ownerId);
    }

    @Test
    void inviteIsMemberScopedWhileRemovalRemainsOwnerOnly() {
        long ownerId = user("owner");
        long memberId = user("member");
        long tripId = trip(ownerId, "authorized");
        TripMembershipResponses.InviteLink invite = memberships.createInvite(ownerId, tripId);
        memberships.join(memberId, invite.token());

        TripMembershipResponses.InviteLink memberInvite = memberships.createInvite(memberId, tripId);
        assertThat(memberInvite.token()).isNotBlank();
        assertDenied(() -> memberships.remove(memberId, tripId, memberId));
        assertDenied(() -> memberships.createInvite(999_999L, tripId));
        assertDenied(() -> memberships.join(999_999L, invite.token()));

        memberships.remove(ownerId, tripId, memberId);

        assertDenied(() -> memberships.createInvite(memberId, tripId));
        assertThat(jdbc.sql("SELECT left_at IS NOT NULL FROM trip_members WHERE trip_id = :tripId AND user_id = :userId")
            .param("tripId", tripId)
            .param("userId", memberId)
            .query(Boolean.class)
            .single()).isTrue();
        assertDenied(() -> planning.addLink(memberId, new BasketRequests.AddLink(
            tripId,
            "naver",
            "https://example.test/removed",
            "removed member place",
            null
        )));
        assertDenied(() -> itineraries.changes(memberId, tripId));
    }

    @Test
    void collectorStateAndModeVersionRemainIndependentPerMember() {
        long ownerId = user("collector-owner");
        long memberId = user("collector-member");
        TripResponses.Created trip = planning.createTrip(
            ownerId,
            new TripRequests.Create("collector trip", "tour", null, null)
        );
        TripMembershipResponses.InviteLink invite = memberships.createInvite(ownerId, trip.id());
        memberships.join(memberId, invite.token());

        TripCollectionResponses.State ownerState = collectionStates.update(
            ownerId,
            trip.id(),
            new TripCollectionRequests.Update("active", "granted", null, 0)
        );
        TripCollectionResponses.State memberState = collectionStates.update(
            memberId,
            trip.id(),
            new TripCollectionRequests.Update("blocked", "denied", "member-cursor", 0)
        );

        assertThat(ownerState.mode_version()).isEqualTo(1);
        assertThat(ownerState.collector_state()).isEqualTo("active");
        assertThat(memberState.mode_version()).isEqualTo(1);
        assertThat(memberState.collector_state()).isEqualTo("blocked");
        assertThat(jdbc.sql(
                """
                SELECT collector_state || '|' || permission_state || '|' || mode_version
                FROM trip_member_collection_states
                WHERE trip_id = :tripId AND user_id = :userId
                """
            )
            .param("tripId", trip.id())
            .param("userId", ownerId)
            .query(String.class)
            .single()).isEqualTo("active|granted|1");
        assertThat(jdbc.sql(
                """
                SELECT collector_state || '|' || permission_state || '|' || mode_version
                FROM trip_member_collection_states
                WHERE trip_id = :tripId AND user_id = :userId
                """
            )
            .param("tripId", trip.id())
            .param("userId", memberId)
            .query(String.class)
            .single()).isEqualTo("blocked|denied|1");
        assertThat(jdbc.sql("SELECT mode FROM trips WHERE id = :tripId")
            .param("tripId", trip.id())
            .query(String.class)
            .single()).isEqualTo("active");

        assertThatThrownBy(() -> collectionStates.update(
            ownerId,
            trip.id(),
            new TripCollectionRequests.Update("dormant", "granted", null, 0)
        ))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
            .isEqualTo(409);
    }

    @Test
    void sharedTripLifecycleIsOwnerControlledAndEndedIsTerminal() {
        long ownerId = user("lifecycle-owner");
        long memberId = user("lifecycle-member");
        TripResponses.Created trip = planning.createTrip(
            ownerId,
            new TripRequests.Create("lifecycle trip", "tour", null, null)
        );
        TripMembershipResponses.InviteLink invite = memberships.createInvite(ownerId, trip.id());
        memberships.join(memberId, invite.token());
        collectionStates.update(
            memberId,
            trip.id(),
            new TripCollectionRequests.Update("active", "granted", null, 0)
        );

        assertThatThrownBy(() -> lifecycle.update(
            memberId,
            trip.id(),
            new TripRequests.Mode("ended")
        ))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
            .isEqualTo(403);
        TripResponses.Mode ended = lifecycle.update(
            ownerId,
            trip.id(),
            new TripRequests.Mode("ended")
        );

        assertThat(ended.mode()).isEqualTo("ended");
        assertThat(ended.ended_at()).isNotNull();
        assertThat(jdbc.sql(
                """
                SELECT COUNT(*)
                FROM trip_member_collection_states
                WHERE trip_id = :tripId AND collector_state = 'ended'
                """
            )
            .param("tripId", trip.id())
            .query(Long.class)
            .single()).isEqualTo(2);
        assertThatThrownBy(() -> lifecycle.update(
            ownerId,
            trip.id(),
            new TripRequests.Mode("active")
        ))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
            .isEqualTo(409);
    }

    @Test
    void lastParticipantDeletesOnlyTheGroupRecordAndPreservesMemberPhotoArchive() {
        long ownerId = user("owner");
        long memberId = user("member");
        long tripId = trip(ownerId, "archive");
        TripMembershipResponses.InviteLink invite = memberships.createInvite(ownerId, tripId);
        memberships.join(memberId, invite.token());
        long photoId = jdbc.sql("""
                INSERT INTO photos (
                    trip_id, user_id, source, original_key, thumb_key
                )
                VALUES (
                    :tripId, :userId, 'camera', :originalKey, :thumbKey
                )
                RETURNING id
                """)
            .param("tripId", tripId)
            .param("userId", memberId)
            .param("originalKey", "task11/member-original.jpg")
            .param("thumbKey", "task11/member-thumb.jpg")
            .query(Long.class)
            .single();

        memberships.leave(memberId, tripId);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trips WHERE id = :tripId")
            .param("tripId", tripId)
            .query(Long.class)
            .single()).isEqualTo(1L);

        memberships.leave(ownerId, tripId);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM trips WHERE id = :tripId")
            .param("tripId", tripId)
            .query(Long.class)
            .single()).isZero();
        assertThat(jdbc.sql("SELECT trip_id IS NULL AND user_id = :memberId FROM photos WHERE id = :photoId")
            .param("memberId", memberId)
            .param("photoId", photoId)
            .query(Boolean.class)
            .single()).isTrue();
    }

    @Test
    void ownerCanDeleteTripOnlyWhenNoActiveMembersRemain() {
        long ownerId = user("owner");
        long memberId = user("member");
        long tripId = trip(ownerId, "delete");
        long changeId = jdbc.sql("""
                INSERT INTO itinerary_changes (trip_id, user_id, action, payload)
                VALUES (:tripId, :ownerId, 'replace', '{}'::jsonb)
                RETURNING id
                """)
            .param("tripId", tripId)
            .param("ownerId", ownerId)
            .query(Long.class)
            .single();
        UUID proposalId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO travel_guide_ai_proposals (
                    id, trip_id, created_by, base_version,
                    proposal_fingerprint, feasible
                )
                VALUES (
                    :proposalId, :tripId, :ownerId, 0,
                    repeat('a', 64), TRUE
                )
                """)
            .param("proposalId", proposalId)
            .param("tripId", tripId)
            .param("ownerId", ownerId)
            .update();
        jdbc.sql("""
                UPDATE travel_guide_ai_proposals
                SET status = 'applied',
                    applied_client_id = :clientId,
                    applied_payload_fingerprint = repeat('a', 64),
                    applied_change_id = :changeId,
                    applied_version = 1,
                    applied_at = CURRENT_TIMESTAMP
                WHERE id = :proposalId
                """)
            .param("clientId", UUID.randomUUID())
            .param("changeId", changeId)
            .param("proposalId", proposalId)
            .update();
        TripMembershipResponses.InviteLink invite = memberships.createInvite(ownerId, tripId);
        memberships.join(memberId, invite.token());

        assertThatThrownBy(() -> memberships.deleteTrip(ownerId, tripId))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
            .isEqualTo(400);

        memberships.leave(memberId, tripId);
        memberships.deleteTrip(ownerId, tripId);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM trips WHERE id = :tripId")
            .param("tripId", tripId)
            .query(Long.class)
            .single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM itinerary_changes WHERE id = :changeId")
            .param("changeId", changeId)
            .query(Long.class)
            .single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM travel_guide_ai_proposals WHERE id = :proposalId")
            .param("proposalId", proposalId)
            .query(Long.class)
            .single()).isZero();
    }

    @Test
    void memberCannotDeleteTrip() {
        long ownerId = user("owner");
        long memberId = user("member");
        long tripId = trip(ownerId, "delete-denied");
        TripMembershipResponses.InviteLink invite = memberships.createInvite(ownerId, tripId);
        memberships.join(memberId, invite.token());

        assertDenied(() -> memberships.deleteTrip(memberId, tripId));
    }

    private void assertDenied(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
            .isEqualTo(403);
    }

    private long user(String nickname) {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", nickname)
            .query(Long.class)
            .single();
    }

    private long trip(long ownerId, String title) {
        long tripId = jdbc.sql("""
                INSERT INTO trips (owner_id, title, activity_type)
                VALUES (:ownerId, :title, 'tour')
                RETURNING id
                """)
            .param("ownerId", ownerId)
            .param("title", title)
            .query(Long.class)
            .single();
        jdbc.sql("INSERT INTO trip_itinerary_states (trip_id) VALUES (:tripId)")
            .param("tripId", tripId)
            .update();
        return tripId;
    }
}
