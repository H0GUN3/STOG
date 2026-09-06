package com.stog.backend.cell;

import com.stog.backend.plan.EffectiveVisibility;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CellRepository {
    private final JdbcClient jdbc;

    public CellRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<SummaryRow> findSummaries(
        CellBounds bounds,
        Long viewerId,
        long afterCellId,
        int limit
    ) {
        String sql = """
            WITH visible_cells AS (
                %s
            ),
            landmark_stats AS (
                %s
            ),
            landmark_content AS (
                %s
            )
            SELECT visible_cells.cell_id,
                   landmark_content.landmark_name,
                   landmark_content.landmark_image_object_key,
                   landmark_content.landmark_image_url,
                   COALESCE(landmark_stats.landmark_count, 0) AS landmark_count,
                   COALESCE(stats.public_photo_count, 0) AS public_photo_count,
                   COALESCE(stats.public_photo_like_count, 0) AS public_photo_like_count,
                   %s AS top_photo_id,
                   %s AS my_visit_count,
                   %s AS my_photo_count,
                   %s AS my_latest_photo_thumbnail_key
            FROM visible_cells
            LEFT JOIN landmark_stats
                ON landmark_stats.cell_id = visible_cells.cell_id
            LEFT JOIN landmark_content
                ON landmark_content.cell_id = visible_cells.cell_id
            LEFT JOIN cell_stats stats ON stats.cell_id = visible_cells.cell_id
            WHERE visible_cells.cell_id > :afterCellId
            ORDER BY visible_cells.cell_id ASC
            LIMIT :limit
            """.formatted(
            visibleCells(bounds != null, viewerId),
            publicLandmarkStats(bounds != null),
            publicLandmarkContent(bounds != null),
            eligibleTopPhoto("visible_cells.cell_id"),
            viewerId == null ? "0" : ownVisitCount("visible_cells.cell_id"),
            viewerId == null ? "0" : ownPhotoCount("visible_cells.cell_id"),
            ownLatestPhotoThumbnailKey("visible_cells.cell_id", viewerId == null)
        );
        List<SummaryRow> validRows = new ArrayList<>(limit);
        long batchAfterCellId = afterCellId;
        while (validRows.size() < limit) {
            List<SummaryRow> batch = bind(jdbc.sql(sql), bounds, viewerId, null)
                .param("afterCellId", batchAfterCellId)
                .param("limit", limit)
                .query((row, rowNumber) -> summary(row))
                .list();
            if (batch.isEmpty()) {
                break;
            }
            for (SummaryRow row : batch) {
                if (CellIdCalculator.isValidCell(row.cellId())) {
                    validRows.add(row);
                    if (validRows.size() == limit) {
                        break;
                    }
                }
            }
            long nextBatchAfterCellId = batch.get(batch.size() - 1).cellId();
            if (batch.size() < limit || nextBatchAfterCellId <= batchAfterCellId) {
                break;
            }
            batchAfterCellId = nextBatchAfterCellId;
        }
        return List.copyOf(validRows);
    }

    public Optional<SummaryRow> findDetail(long cellId, Long viewerId) {
        String sql = """
            WITH visible_cells AS (
                %s
            ),
            landmark_stats AS (
                %s
            ),
            landmark_content AS (
                %s
            )
            SELECT visible_cells.cell_id,
                   landmark_content.landmark_name,
                   landmark_content.landmark_image_object_key,
                   landmark_content.landmark_image_url,
                   COALESCE(landmark_stats.landmark_count, 0) AS landmark_count,
                   COALESCE(stats.public_photo_count, 0) AS public_photo_count,
                   COALESCE(stats.public_photo_like_count, 0) AS public_photo_like_count,
                   %s AS top_photo_id,
                   %s AS my_visit_count,
                   %s AS my_photo_count,
                   %s AS my_latest_photo_thumbnail_key
            FROM visible_cells
            LEFT JOIN landmark_stats
                ON landmark_stats.cell_id = visible_cells.cell_id
            LEFT JOIN landmark_content
                ON landmark_content.cell_id = visible_cells.cell_id
            LEFT JOIN cell_stats stats ON stats.cell_id = visible_cells.cell_id
            """.formatted(
            visibleCells(false, viewerId),
            publicLandmarkStats(false),
            publicLandmarkContent(false),
            eligibleTopPhoto("visible_cells.cell_id"),
            viewerId == null ? "0" : ownVisitCount("visible_cells.cell_id"),
            viewerId == null ? "0" : ownPhotoCount("visible_cells.cell_id"),
            ownLatestPhotoThumbnailKey("visible_cells.cell_id", viewerId == null)
        );
        return bind(jdbc.sql(sql), null, viewerId, cellId)
            .query((row, rowNumber) -> summary(row))
            .optional()
            .filter(row -> CellIdCalculator.isValidCell(row.cellId()));
    }

    public VisibilityReasons findVisibilityReasons(long cellId, Long viewerId) {
        String sql = """
            SELECT EXISTS (%s) AS licensed_landmark,
                   EXISTS (%s) AS eligible_public_photo,
                   %s AS my_visit,
                   %s AS my_photo,
                   %s AS authorized_group_photo
            """.formatted(
            publicLandmark(false),
            publicPhoto(false),
            viewerId == null ? "FALSE" : "EXISTS (" + ownVisit(false) + ")",
            viewerId == null ? "FALSE" : "EXISTS (" + ownPhoto(false) + ")",
            viewerId == null ? "FALSE" : "EXISTS (" + groupPhoto(false) + ")"
        );
        return bind(jdbc.sql(sql), null, viewerId, cellId)
            .query((row, rowNumber) -> new VisibilityReasons(
                row.getBoolean("licensed_landmark"),
                row.getBoolean("eligible_public_photo"),
                row.getBoolean("my_visit"),
                row.getBoolean("my_photo"),
                row.getBoolean("authorized_group_photo")
            ))
            .single();
    }

    public List<PhotoRow> findPhotos(
        long cellId,
        Long viewerId,
        CellPhotoCursor cursor,
        int limit
    ) {
        return findPhotos(cellId, viewerId, cursor, limit, false);
    }

    public List<PhotoRow> findPhotos(
        long cellId,
        Long viewerId,
        CellPhotoCursor cursor,
        int limit,
        boolean mineOnly
    ) {
        String viewerLike = viewerId == null
            ? "FALSE"
            : """
                EXISTS (
                    SELECT 1
                    FROM likes viewer_like
                    WHERE viewer_like.user_id = :viewerId
                      AND viewer_like.target_type = 'photo'
                      AND viewer_like.target_id = p.id
                )
                """;
        String cursorClause = cursor == null
            ? ""
            : """
                AND (
                    p.created_at < :cursorCreatedAt
                    OR (
                        p.created_at = :cursorCreatedAt
                        AND p.id < :cursorPhotoId
                    )
                )
                """;
        String visibility = mineOnly
            ? "p.user_id = :viewerId"
            : viewerId == null
            ? EffectiveVisibility.CELL_PHOTO_ELIGIBILITY_SQL
            : """
                (%s)
                OR p.user_id = :viewerId
                OR (
                    p.user_id <> :viewerId
                    AND t.visibility IN ('group', 'public')
                    AND p.visibility IN ('group', 'public')
                    AND (
                        t.owner_id = :viewerId
                        OR EXISTS (
                            SELECT 1
                            FROM trip_members member
                            WHERE member.trip_id = t.id
                              AND member.user_id = :viewerId
                              AND member.left_at IS NULL
                        )
                    )
                )
                """.formatted(EffectiveVisibility.CELL_PHOTO_ELIGIBILITY_SQL);
        String sql = """
            SELECT p.id,
                   p.cell_id,
                   p.lat,
                   p.lng,
                   p.thumb_key,
                   p.taken_at,
                   p.caption,
                   p.like_count,
                   %s AS liked_by_viewer,
                   p.location_accuracy_m,
                   CASE
                       WHEN EXISTS (
                           SELECT 1 FROM places current_place
                           WHERE current_place.id = p.place_id
                             AND current_place.catalog_status <> 'quarantined'
                       ) THEN p.place_id
                       ELSE NULL
                   END AS place_id,
                   p.place_name_snapshot,
                   p.place_resolution_status,
                   p.visibility,
                   p.moderation_status,
                   %s AS publication_status,
                   CASE
                       WHEN %s THEN 'public'
                       WHEN p.trip_id IS NULL THEN 'private'
                       WHEN t.visibility = 'private' OR p.visibility = 'private' THEN 'private'
                       WHEN t.visibility = 'group' OR p.visibility = 'group' THEN 'group'
                       ELSE 'public'
                   END AS visibility_scope,
                   p.created_at
            FROM photos p
            LEFT JOIN trips t ON t.id = p.trip_id
            WHERE p.cell_id = :cellId
              AND p.lat IS NOT NULL
              AND p.lng IS NOT NULL
              AND p.moderation_status <> 'blocked'
              AND (%s)
              %s
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT :limit
            """.formatted(
            viewerLike,
            EffectiveVisibility.CURRENT_PHOTO_PUBLICATION_STATUS_SQL,
            EffectiveVisibility.CELL_PHOTO_ELIGIBILITY_SQL,
            visibility,
            cursorClause
        );
        JdbcClient.StatementSpec statement = bind(jdbc.sql(sql), null, viewerId, cellId)
            .param("limit", limit);
        if (cursor != null) {
            statement = statement
                .param("cursorCreatedAt", Timestamp.from(cursor.createdAt()))
                .param("cursorPhotoId", cursor.photoId());
        }
        return statement.query((row, rowNumber) -> new PhotoRow(
            row.getLong("id"),
            row.getLong("cell_id"),
            row.getDouble("lat"),
            row.getDouble("lng"),
            row.getString("thumb_key"),
            instant(row.getTimestamp("taken_at")),
            row.getString("caption"),
            row.getLong("like_count"),
            row.getBoolean("liked_by_viewer"),
            row.getString("visibility_scope"),
            row.getObject("location_accuracy_m", Double.class),
            nullableLong(row.getObject("place_id")),
            row.getString("place_name_snapshot"),
            row.getString("place_resolution_status"),
            row.getString("visibility"),
            row.getString("moderation_status"),
            row.getString("publication_status"),
            instant(row.getTimestamp("created_at"))
        )).list().stream()
            .filter(row -> CellIdCalculator.isValidCell(row.cellId()))
            .toList();
    }

    private String visibleCells(boolean bounded, Long viewerId) {
        String sources = publicLandmark(bounded) + "\nUNION\n" + publicPhoto(bounded);
        if (viewerId == null) {
            return sources;
        }
        return sources + "\nUNION\n" + ownVisit(bounded)
            + "\nUNION\n" + ownPhoto(bounded)
            + "\nUNION\n" + groupPhoto(bounded);
    }

    private String publicLandmark(boolean bounded) {
        return """
            SELECT place.cell_id
            FROM places place
            JOIN place_source_records source_record ON source_record.place_id = place.id
            JOIN catalog_sources catalog_source ON catalog_source.id = source_record.catalog_source_id
            JOIN license_snapshots license_snapshot
                ON license_snapshot.id = source_record.license_snapshot_id
            WHERE place.catalog_status = 'public'
              AND place.public_cell_eligible
              AND source_record.active
              AND catalog_source.active
              AND license_snapshot.allows_public_discovery
              AND license_snapshot.valid_from <= CURRENT_DATE
              AND (license_snapshot.valid_until IS NULL OR license_snapshot.valid_until >= CURRENT_DATE)
              AND jsonb_array_length(license_snapshot.reusable_fields) > 0
              AND %s
              %s
            """.formatted(canonicalCell("place.cell_id"), coordinateBounds("place", bounded));
    }

    private String publicLandmarkStats(boolean bounded) {
        return """
            SELECT place.cell_id, COUNT(DISTINCT place.id) AS landmark_count
            FROM places place
            JOIN place_source_records source_record ON source_record.place_id = place.id
            JOIN catalog_sources catalog_source ON catalog_source.id = source_record.catalog_source_id
            JOIN license_snapshots license_snapshot
                ON license_snapshot.id = source_record.license_snapshot_id
            WHERE place.catalog_status = 'public'
              AND place.public_cell_eligible
              AND source_record.active
              AND catalog_source.active
              AND license_snapshot.allows_public_discovery
              AND license_snapshot.valid_from <= CURRENT_DATE
              AND (license_snapshot.valid_until IS NULL OR license_snapshot.valid_until >= CURRENT_DATE)
              AND jsonb_array_length(license_snapshot.reusable_fields) > 0
              AND place.cell_id IS NOT NULL
              AND place.cell_id > 0
              AND ((place.cell_id >> 52) & 15) = 10
              %s
            GROUP BY place.cell_id
            """.formatted(coordinateBounds("place", bounded));
    }

    private String publicLandmarkContent(boolean bounded) {
        return """
            SELECT DISTINCT ON (place.cell_id)
                   place.cell_id,
                   place.name AS landmark_name,
                   source_image.object_key AS landmark_image_object_key,
                   source_image.source_url AS landmark_image_url
            FROM places place
            JOIN place_source_records source_record
                ON source_record.place_id = place.id
            JOIN catalog_sources catalog_source
                ON catalog_source.id = source_record.catalog_source_id
            JOIN license_snapshots license_snapshot
                ON license_snapshot.id = source_record.license_snapshot_id
            LEFT JOIN place_source_images source_image
                ON source_image.place_source_record_id = source_record.id
               AND source_image.reusable
               AND source_image.source_url IS NOT NULL
               AND source_image.license_snapshot_id = license_snapshot.id
               AND lower(source_image.source_url) LIKE 'https://%%'
               AND jsonb_exists(license_snapshot.reusable_fields, 'image')
            WHERE place.catalog_status = 'public'
              AND place.public_cell_eligible
              AND source_record.active
              AND catalog_source.active
              AND license_snapshot.allows_public_discovery
              AND license_snapshot.valid_from <= CURRENT_DATE
              AND (license_snapshot.valid_until IS NULL OR license_snapshot.valid_until >= CURRENT_DATE)
              AND jsonb_exists(license_snapshot.reusable_fields, 'name')
              AND %s
              %s
            ORDER BY place.cell_id,
                     source_image.source_url IS NULL,
                     place.id,
                     source_image.id
            """.formatted(
            canonicalCell("place.cell_id"),
            coordinateBounds("place", bounded)
        );
    }

    private String publicPhoto(boolean bounded) {
        return """
            SELECT p.cell_id
            FROM photos p
            JOIN trips t ON t.id = p.trip_id
            WHERE p.lat IS NOT NULL
              AND p.lng IS NOT NULL
              AND %s
              AND %s
              %s
            """.formatted(
            canonicalCell("p.cell_id"),
            EffectiveVisibility.CELL_PHOTO_ELIGIBILITY_SQL,
            coordinateBounds("p", bounded)
        );
    }

    private String ownVisit(boolean bounded) {
        return """
            SELECT visit.cell_id
            FROM visits visit
            WHERE visit.user_id = :viewerId
              AND visit.status = 'visited'
              AND NOT visit.is_interpolated
              AND %s
              %s
            """.formatted(canonicalCell("visit.cell_id"), coordinateBounds("visit", bounded));
    }

    private String ownPhoto(boolean bounded) {
        return """
            SELECT photo.cell_id
            FROM photos photo
            WHERE photo.user_id = :viewerId
              AND photo.lat IS NOT NULL
              AND photo.lng IS NOT NULL
              AND photo.moderation_status <> 'blocked'
              AND %s
              %s
            """.formatted(canonicalCell("photo.cell_id"), coordinateBounds("photo", bounded));
    }

    private String groupPhoto(boolean bounded) {
        return """
            SELECT photo.cell_id
            FROM photos photo
            JOIN trips trip ON trip.id = photo.trip_id
            WHERE photo.user_id <> :viewerId
              AND photo.lat IS NOT NULL
              AND photo.lng IS NOT NULL
              AND photo.moderation_status <> 'blocked'
              AND trip.visibility IN ('group', 'public')
              AND photo.visibility IN ('group', 'public')
              AND (
                  trip.owner_id = :viewerId
                  OR EXISTS (
                      SELECT 1
                      FROM trip_members member
                      WHERE member.trip_id = trip.id
                        AND member.user_id = :viewerId
                        AND member.left_at IS NULL
                  )
              )
              AND %s
              %s
            """.formatted(canonicalCell("photo.cell_id"), coordinateBounds("photo", bounded));
    }

    private String eligibleTopPhoto(String cellReference) {
        return """
            CASE
                WHEN stats.top_photo_id IS NOT NULL
                     AND EXISTS (
                         SELECT 1
                         FROM photos p
                         JOIN trips t ON t.id = p.trip_id
                         WHERE p.id = stats.top_photo_id
                           AND p.cell_id = %s
                           AND p.lat IS NOT NULL
                           AND p.lng IS NOT NULL
                           AND %s
                     )
                THEN stats.top_photo_id
                ELSE NULL
            END
            """.formatted(cellReference, EffectiveVisibility.CELL_PHOTO_ELIGIBILITY_SQL);
    }

    private String ownVisitCount(String cellReference) {
        return """
            (
                SELECT COUNT(*)
                FROM visits visit
                WHERE visit.user_id = :viewerId
                  AND visit.cell_id = %s
                  AND visit.status = 'visited'
                  AND NOT visit.is_interpolated
            )
            """.formatted(cellReference);
    }

    private String ownPhotoCount(String cellReference) {
        return """
            (
                SELECT COUNT(*)
                FROM photos photo
                WHERE photo.user_id = :viewerId
                  AND photo.cell_id = %s
                  AND photo.lat IS NOT NULL
                  AND photo.lng IS NOT NULL
                  AND photo.moderation_status <> 'blocked'
            )
            """.formatted(cellReference);
    }

    private String ownLatestPhotoThumbnailKey(String cellReference, boolean guest) {
        if (guest) {
            return "NULL";
        }
        return """
            (
                SELECT photo.thumb_key
                FROM photos photo
                WHERE photo.user_id = :viewerId
                  AND photo.cell_id = %s
                  AND photo.lat IS NOT NULL
                  AND photo.lng IS NOT NULL
                  AND photo.moderation_status <> 'blocked'
                ORDER BY COALESCE(photo.taken_at, photo.created_at) DESC,
                         photo.id DESC
                LIMIT 1
            )
            """.formatted(cellReference);
    }

    private String coordinateBounds(String alias, boolean bounded) {
        if (!bounded) {
            return "AND " + alias + ".cell_id = :cellId";
        }
        return """
            AND %s.lat >= :swLat
            AND %s.lat <= :neLat
            AND %s.lng >= :swLng
            AND %s.lng <= :neLng
            """.formatted(alias, alias, alias, alias);
    }

    private String canonicalCell(String reference) {
        return """
            %s IS NOT NULL
            AND %s > 0
            AND ((%s >> 52) & 15) = 10
            """.formatted(reference, reference, reference);
    }

    private JdbcClient.StatementSpec bind(
        JdbcClient.StatementSpec statement,
        CellBounds bounds,
        Long viewerId,
        Long cellId
    ) {
        if (bounds != null) {
            statement = statement
                .param("swLat", bounds.swLat())
                .param("swLng", bounds.swLng())
                .param("neLat", bounds.neLat())
                .param("neLng", bounds.neLng());
        }
        if (viewerId != null) {
            statement = statement.param("viewerId", viewerId);
        }
        if (cellId != null) {
            statement = statement.param("cellId", cellId);
        }
        return statement;
    }

    private SummaryRow summary(java.sql.ResultSet row) throws java.sql.SQLException {
        return new SummaryRow(
            row.getLong("cell_id"),
            row.getString("landmark_name"),
            row.getString("landmark_image_object_key"),
            row.getString("landmark_image_url"),
            row.getLong("landmark_count"),
            row.getLong("public_photo_count"),
            row.getLong("public_photo_like_count"),
            nullableLong(row.getObject("top_photo_id")),
            row.getLong("my_visit_count"),
            row.getLong("my_photo_count"),
            row.getString("my_latest_photo_thumbnail_key")
        );
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record SummaryRow(
        long cellId,
        String landmarkName,
        String landmarkImageObjectKey,
        String landmarkImageUrl,
        long landmarkCount,
        long publicPhotoCount,
        long publicPhotoLikeCount,
        Long topPhotoId,
        long myVisitCount,
        long myPhotoCount,
        String myLatestPhotoThumbnailKey
    ) {
    }

    public record VisibilityReasons(
        boolean licensedLandmark,
        boolean eligiblePublicPhoto,
        boolean myVisit,
        boolean myPhoto,
        boolean authorizedGroupPhoto
    ) {
    }

    public record PhotoRow(
        long id,
        long cellId,
        double latitude,
        double longitude,
        String thumbnailKey,
        Instant takenAt,
        String caption,
        long likeCount,
        boolean likedByViewer,
        String visibilityScope,
        Double accuracyMeters,
        Long placeId,
        String placeNameSnapshot,
        String placeResolutionStatus,
        String visibility,
        String moderationStatus,
        String publicationStatus,
        Instant createdAt
    ) {
    }
}
