package com.stog.backend.plan;

import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TravelGuideAiProposalRepository {
    private final JdbcClient jdbc;

    public TravelGuideAiProposalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<BasketCoordinate> basketCoordinates(long tripId, List<Long> basketItemIds) {
        if (basketItemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                """
                SELECT id, lat, lng FROM basket_items
                WHERE trip_id = :tripId AND id IN (:ids)
                  AND status IN ('resolved', 'manual') AND lat IS NOT NULL AND lng IS NOT NULL
                ORDER BY id
                """
            )
            .param("tripId", tripId)
            .param("ids", basketItemIds)
            .query((row, rowNumber) -> new BasketCoordinate(
                row.getLong("id"), row.getDouble("lat"), row.getDouble("lng")
            ))
            .list();
    }

    public List<BasketPlaceDetails> basketPlaceDetails(long tripId, List<Long> basketItemIds) {
        if (basketItemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                """
                SELECT basket.id AS basket_item_id,
                       COALESCE(place.category, basket.category) AS category,
                       CASE WHEN jsonb_exists(details.traits, 'nature')
                            THEN CASE WHEN (details.traits ->> 'nature')::boolean
                                      THEN 1.0 ELSE 0.0 END END::double precision AS nature_trait,
                       CASE WHEN jsonb_exists(details.traits, 'culture')
                            THEN CASE WHEN (details.traits ->> 'culture')::boolean
                                      THEN 1.0 ELSE 0.0 END END::double precision AS culture_trait,
                       CASE WHEN jsonb_exists(details.traits, 'food')
                            THEN CASE WHEN (details.traits ->> 'food')::boolean
                                      THEN 1.0 ELSE 0.0 END END::double precision AS food_trait,
                       CASE WHEN jsonb_exists(details.traits, 'shopping')
                            THEN CASE WHEN (details.traits ->> 'shopping')::boolean
                                      THEN 1.0 ELSE 0.0 END END::double precision AS shopping_trait,
                       CASE WHEN jsonb_exists(details.traits, 'experience')
                            THEN CASE WHEN (details.traits ->> 'experience')::boolean
                                      THEN 1.0 ELSE 0.0 END END::double precision AS experience_trait,
                       CASE WHEN jsonb_exists(details.traits, 'relaxation')
                            THEN CASE WHEN (details.traits ->> 'relaxation')::boolean
                                      THEN 1.0 ELSE 0.0 END END::double precision AS relaxation_trait,
                       details.opening_hours::text AS opening_hours_json,
                       COALESCE(details.visit_minutes_override, details.visit_minutes)
                           AS visit_minutes
                FROM basket_items basket
                LEFT JOIN places place ON place.id = basket.place_id
                LEFT JOIN place_details details ON details.place_id = place.id
                WHERE basket.trip_id = :tripId AND basket.id IN (:ids)
                ORDER BY basket.id
                """
            )
            .param("tripId", tripId)
            .param("ids", basketItemIds)
            .query((row, rowNumber) -> new BasketPlaceDetails(
                row.getLong("basket_item_id"),
                row.getString("category"),
                row.getObject("nature_trait", Double.class),
                row.getObject("culture_trait", Double.class),
                row.getObject("food_trait", Double.class),
                row.getObject("shopping_trait", Double.class),
                row.getObject("experience_trait", Double.class),
                row.getObject("relaxation_trait", Double.class),
                row.getString("opening_hours_json"),
                row.getObject("visit_minutes", Integer.class)
            ))
            .list();
    }

    public Optional<LocalDate> tripStartDate(long tripId) {
        return jdbc.sql(
                "SELECT planned_start_date FROM trips WHERE id = :tripId"
            )
            .param("tripId", tripId)
            .query(LocalDate.class)
            .optional();
    }

    public Set<Long> resolvedBasketItemIds(long tripId, List<Long> basketItemIds) {
        if (basketItemIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql(
                """
                SELECT id FROM basket_items
                WHERE trip_id = :tripId AND id IN (:ids)
                  AND status IN ('resolved', 'manual') AND lat IS NOT NULL AND lng IS NOT NULL
                ORDER BY id
                """
            )
            .param("tripId", tripId)
            .param("ids", basketItemIds)
            .query(Long.class)
            .list());
    }

    public void save(Draft draft) {
        jdbc.sql(
                """
                INSERT INTO travel_guide_ai_proposals (
                    id, trip_id, created_by, base_version, proposal_fingerprint, feasible, status
                ) VALUES (
                    :id, :tripId, :createdBy, :baseVersion, :fingerprint, :feasible, :status
                )
                """
            )
            .param("id", draft.id())
            .param("tripId", draft.tripId())
            .param("createdBy", draft.createdBy())
            .param("baseVersion", draft.baseVersion())
            .param("fingerprint", draft.fingerprint())
            .param("feasible", draft.feasible())
            .param("status", draft.feasible() ? "ready" : "invalid")
            .update();
        for (TravelGuideAiResponses.Action action : draft.actions()) {
            jdbc.sql(
                    """
                    INSERT INTO travel_guide_ai_proposal_actions (
                        proposal_id, action_order, basket_item_id, day_number, order_index,
                        planned_arrival, planned_duration_min, travel_minutes_from_previous, is_fixed
                    ) VALUES (
                        :proposalId, :actionOrder, :basketItemId, :dayNumber, :orderIndex,
                        :plannedArrival, :duration, :travelMinutes, :isFixed
                    )
                    """
                )
                .param("proposalId", draft.id())
                .param("actionOrder", action.action_order())
                .param("basketItemId", action.basket_item_id())
                .param("dayNumber", action.day_number())
                .param("orderIndex", action.order_index())
                .param("plannedArrival", Time.valueOf(LocalTime.parse(action.planned_arrival())))
                .param("duration", action.planned_duration_min())
                .param("travelMinutes", action.travel_minutes_from_previous())
                .param("isFixed", action.is_fixed())
                .update();
        }
        for (int index = 0; index < draft.exclusions().size(); index++) {
            jdbc.sql(
                    """
                    INSERT INTO travel_guide_ai_proposal_exclusions (
                        proposal_id, exclusion_order, basket_item_id
                    ) VALUES (:proposalId, :position, :basketItemId)
                    """
                )
                .param("proposalId", draft.id())
                .param("position", index)
                .param("basketItemId", draft.exclusions().get(index))
                .update();
        }
        for (int index = 0; index < draft.violations().size(); index++) {
            TravelGuideAiResponses.Violation violation = draft.violations().get(index);
            jdbc.sql(
                    """
                    INSERT INTO travel_guide_ai_proposal_violations (
                        proposal_id, violation_order, code, basket_item_id
                    ) VALUES (:proposalId, :position, :code, :basketItemId)
                    """
                )
                .param("proposalId", draft.id())
                .param("position", index)
                .param("code", violation.code())
                .param("basketItemId", violation.basket_item_id())
                .update();
        }
    }

    public Optional<StoredProposal> lock(UUID id) {
        return jdbc.sql(
                """
                SELECT id, trip_id, base_version, proposal_fingerprint, feasible, status,
                       applied_client_id, applied_payload_fingerprint,
                       applied_change_id, applied_version
                FROM travel_guide_ai_proposals WHERE id = :id FOR UPDATE
                """
            )
            .param("id", id)
            .query((row, rowNumber) -> new StoredProposal(
                row.getObject("id", UUID.class), row.getLong("trip_id"), row.getLong("base_version"),
                row.getString("proposal_fingerprint"), row.getBoolean("feasible"),
                row.getString("status"), row.getObject("applied_client_id", UUID.class),
                row.getString("applied_payload_fingerprint"),
                row.getObject("applied_change_id", Long.class),
                row.getObject("applied_version", Long.class), actions(id)
            ))
            .optional();
    }

    private List<TravelGuideAiResponses.Action> actions(UUID id) {
        return jdbc.sql(
                """
                SELECT action_order, basket_item_id, day_number, order_index,
                       planned_arrival, planned_duration_min,
                       travel_minutes_from_previous, is_fixed
                FROM travel_guide_ai_proposal_actions
                WHERE proposal_id = :id ORDER BY action_order
                """
            )
            .param("id", id)
            .query((row, rowNumber) -> new TravelGuideAiResponses.Action(
                row.getInt("action_order"), "set_itinerary_item", row.getLong("basket_item_id"),
                row.getInt("day_number"), row.getInt("order_index"),
                row.getObject("planned_arrival", LocalTime.class).toString(),
                row.getInt("planned_duration_min"), row.getInt("travel_minutes_from_previous"),
                row.getBoolean("is_fixed")
            ))
            .list();
    }

    public void markApplied(ApplyReceipt receipt) {
        jdbc.sql(
                """
                UPDATE travel_guide_ai_proposals
                SET status = 'applied', applied_client_id = :clientApplyId,
                    applied_payload_fingerprint = :payloadFingerprint,
                    applied_change_id = :changeId, applied_version = :version,
                    applied_at = CURRENT_TIMESTAMP
                WHERE id = :proposalId
                """
            )
            .param("clientApplyId", receipt.clientApplyId())
            .param("payloadFingerprint", receipt.payloadFingerprint())
            .param("changeId", receipt.write().changeId())
            .param("version", receipt.write().version())
            .param("proposalId", receipt.proposalId())
            .update();
    }

    public record BasketCoordinate(long basketItemId, double latitude, double longitude) {
    }

    public record BasketPlaceDetails(
        long basketItemId,
        String category,
        Double natureTrait,
        Double cultureTrait,
        Double foodTrait,
        Double shoppingTrait,
        Double experienceTrait,
        Double relaxationTrait,
        String openingHoursJson,
        Integer visitMinutes
    ) {
    }

    public record ApplyReceipt(
        UUID proposalId,
        UUID clientApplyId,
        String payloadFingerprint,
        ItineraryRepository.AppliedWrite write
    ) {
    }

    public record Draft(
        UUID id,
        long tripId,
        long createdBy,
        long baseVersion,
        String fingerprint,
        boolean feasible,
        List<TravelGuideAiResponses.Action> actions,
        List<Long> exclusions,
        List<TravelGuideAiResponses.Violation> violations
    ) {
        public Draft {
            actions = List.copyOf(actions);
            exclusions = List.copyOf(exclusions);
            violations = List.copyOf(violations);
        }
    }

    public record StoredProposal(
        UUID id,
        long tripId,
        long baseVersion,
        String fingerprint,
        boolean feasible,
        String status,
        UUID appliedClientId,
        String appliedPayloadFingerprint,
        Long appliedChangeId,
        Long appliedVersion,
        List<TravelGuideAiResponses.Action> actions
    ) {
        public StoredProposal {
            actions = List.copyOf(actions);
        }
    }
}
