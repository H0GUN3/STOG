package com.stog.backend.plan;

import com.stog.backend.cell.CellIdCalculator;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class VisitRepository {
    private static final RowMapper<VisitResponses.Recorded> RECORDED_MAPPER =
        (row, rowNumber) -> new VisitResponses.Recorded(
            row.getLong("id"),
            row.getObject("client_visit_id", UUID.class),
            row.getString("payload_fingerprint"),
            CellIdCalculator.toWire(row.getLong("cell_id")),
            row.getString("status"),
            row.getBoolean("is_interpolated"),
            row.getBoolean("review_required"),
            row.getTimestamp("created_at").toInstant()
        );

    private final JdbcClient jdbc;

    public VisitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<VisitResponses.Recorded> find(
        long userId,
        long tripId,
        UUID clientVisitId
    ) {
        return jdbc.sql("""
                SELECT visit.id, visit.client_visit_id, visit.payload_fingerprint,
                       visit.cell_id, visit.status, visit.is_interpolated,
                       receipt.review_required, visit.created_at
                FROM visits visit
                JOIN visit_review_receipts receipt ON receipt.visit_id = visit.id
                WHERE visit.user_id = :userId
                  AND visit.trip_id = :tripId
                  AND visit.client_visit_id = :clientVisitId
                """)
            .param("userId", userId)
            .param("tripId", tripId)
            .param("clientVisitId", clientVisitId)
            .query(RECORDED_MAPPER)
            .optional();
    }

    public Optional<VisitResponses.Recorded> create(
        long userId,
        long tripId,
        long cellId,
        VisitRequests.Record request,
        VisitDecision.Result decision
    ) {
        return jdbc.sql("""
                WITH inserted AS (
                    INSERT INTO visits (
                        user_id,
                        trip_id,
                        client_visit_id,
                        payload_fingerprint,
                        cell_id,
                        lat,
                        lng,
                        entered_at,
                        left_at,
                        status,
                        is_interpolated
                    )
                    VALUES (
                        :userId,
                        :tripId,
                        :clientVisitId,
                        :payloadFingerprint,
                        :cellId,
                        :latitude,
                        :longitude,
                        :enteredAt,
                        :leftAt,
                        :status,
                        :isInterpolated
                    )
                    ON CONFLICT (trip_id, user_id, client_visit_id) DO NOTHING
                    RETURNING id, client_visit_id, payload_fingerprint, cell_id, status,
                              is_interpolated, created_at
                ), receipt AS (
                    INSERT INTO visit_review_receipts (visit_id, review_required)
                    SELECT id, status = 'visited' FROM inserted
                    RETURNING visit_id, review_required
                )
                SELECT inserted.id, inserted.client_visit_id, inserted.payload_fingerprint,
                       inserted.cell_id, inserted.status, inserted.is_interpolated,
                       receipt.review_required, inserted.created_at
                FROM inserted JOIN receipt ON receipt.visit_id = inserted.id
                """)
            .param("userId", userId)
            .param("tripId", tripId)
            .param("clientVisitId", request.client_visit_id())
            .param("payloadFingerprint", request.payload_fingerprint())
            .param("cellId", cellId)
            .param("latitude", request.lat())
            .param("longitude", request.lng())
            .param("enteredAt", Timestamp.from(request.entered_at()))
            .param("leftAt", Timestamp.from(request.left_at()))
            .param("status", decision.status().value())
            .param("isInterpolated", request.is_interpolated())
            .query(RECORDED_MAPPER)
            .optional();
    }
}
