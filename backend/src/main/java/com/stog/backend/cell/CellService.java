package com.stog.backend.cell;

import com.stog.backend.storage.GcsReadUrlSigner;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CellService {
    private final CellRepository cells;
    private final CellProperties properties;
    private final ObjectProvider<GcsReadUrlSigner> signedReads;

    public CellService(
        CellRepository cells,
        CellProperties properties,
        ObjectProvider<GcsReadUrlSigner> signedReads
    ) {
        this.cells = cells;
        this.properties = properties;
        this.signedReads = signedReads;
    }

    @Transactional(readOnly = true)
    public CellResponses.Page summaries(
        Long viewerId,
        Double swLat,
        Double swLng,
        Double neLat,
        Double neLng,
        String cursorValue,
        Integer requestedLimit
    ) {
        CellBounds bounds = bounds(swLat, swLng, neLat, neLng);
        CellCursor cursor = cursorValue == null ? null : CellCursor.decode(cursorValue);
        int limit = pageSize(requestedLimit, properties.summaryPageSize(), "Cell summary");
        List<CellRepository.SummaryRow> rows = cells.findSummaries(
            bounds,
            viewerId,
            cursor == null ? 0L : cursor.cellId(),
            limit + 1
        );
        boolean hasNext = rows.size() > limit;
        List<CellRepository.SummaryRow> page = hasNext ? rows.subList(0, limit) : rows;
        String nextCursor = hasNext
            ? new CellCursor(page.get(page.size() - 1).cellId()).encode()
            : null;
        return new CellResponses.Page(page.stream().map(this::summary).toList(), nextCursor);
    }

    @Transactional(readOnly = true)
    public CellResponses.Detail detail(Long viewerId, String cellIdValue) {
        long cellId = cellId(cellIdValue);
        CellRepository.SummaryRow row = cells.findDetail(cellId, viewerId)
            .orElseThrow(() -> notFound(cellIdValue));
        CellRepository.VisibilityReasons reasons = cells.findVisibilityReasons(cellId, viewerId);
        return new CellResponses.Detail(
            CellIdCalculator.toWire(row.cellId()),
            background(row),
            badges(row),
            row.landmarkName(),
            landmarkImageUrl(row),
            row.landmarkCount(),
            row.publicPhotoCount(),
            row.publicPhotoLikeCount(),
            row.topPhotoId(),
            row.myVisitCount(),
            row.myPhotoCount(),
            thumbnailUrl(row.myLatestPhotoThumbnailKey()),
            visibilityReasons(reasons),
            CellIdCalculator.centroid(row.cellId()),
            CellIdCalculator.boundary(row.cellId())
        );
    }

    @Transactional(readOnly = true)
    public CellResponses.PhotoPage photos(
        Long viewerId,
        String cellIdValue,
        String cursorValue,
        Integer requestedLimit
    ) {
        return photos(viewerId, cellIdValue, cursorValue, requestedLimit, null);
    }

    @Transactional(readOnly = true)
    public CellResponses.PhotoPage photos(
        Long viewerId,
        String cellIdValue,
        String cursorValue,
        Integer requestedLimit,
        String scope
    ) {
        long cellId = cellId(cellIdValue);
        boolean mineOnly = "mine".equals(scope);
        if (scope != null && !scope.isBlank() && !mineOnly) {
            throw badRequest("photo scope is unsupported");
        }
        if (mineOnly && viewerId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (cells.findDetail(cellId, viewerId).isEmpty()) {
            throw notFound(cellIdValue);
        }
        CellPhotoCursor cursor = cursorValue == null ? null : CellPhotoCursor.decode(cursorValue);
        int limit = pageSize(requestedLimit, properties.photoPageSize(), "Cell photo");
        List<CellRepository.PhotoRow> rows = cells.findPhotos(
            cellId, viewerId, cursor, limit + 1, mineOnly
        );
        boolean hasNext = rows.size() > limit;
        List<CellRepository.PhotoRow> page = hasNext ? rows.subList(0, limit) : rows;
        String nextCursor = hasNext
            ? new CellPhotoCursor(
                page.get(page.size() - 1).createdAt(),
                page.get(page.size() - 1).id()
            ).encode()
            : null;
        return new CellResponses.PhotoPage(page.stream().map(this::photo).toList(), nextCursor);
    }

    private CellBounds bounds(Double swLat, Double swLng, Double neLat, Double neLng) {
        try {
            return CellBounds.of(swLat, swLng, neLat, neLng);
        } catch (IllegalArgumentException error) {
            throw badRequest(error.getMessage());
        }
    }

    private long cellId(String value) {
        try {
            return CellIdCalculator.fromWire(value);
        } catch (IllegalArgumentException error) {
            throw badRequest("cell_id is invalid");
        }
    }

    private int pageSize(Integer requested, int configuredMaximum, String name) {
        if (requested == null) {
            return configuredMaximum;
        }
        if (requested < 1 || requested > configuredMaximum) {
            throw badRequest(name + " limit exceeds the configured page size");
        }
        return requested;
    }

    private CellResponses.Summary summary(CellRepository.SummaryRow row) {
        return new CellResponses.Summary(
            CellIdCalculator.toWire(row.cellId()),
            background(row),
            badges(row),
            row.landmarkName(),
            landmarkImageUrl(row),
            row.landmarkCount(),
            row.publicPhotoCount(),
            row.publicPhotoLikeCount(),
            row.topPhotoId(),
            row.myVisitCount(),
            row.myPhotoCount(),
            thumbnailUrl(row.myLatestPhotoThumbnailKey()),
            CellIdCalculator.centroid(row.cellId()),
            CellIdCalculator.boundary(row.cellId())
        );
    }

    private CellResponses.Photo photo(CellRepository.PhotoRow row) {
        return new CellResponses.Photo(
            row.id(),
            CellIdCalculator.toWire(row.cellId()),
            row.latitude(),
            row.longitude(),
            thumbnailUrl(row.thumbnailKey()),
            row.takenAt(),
            row.caption(),
            row.likeCount(),
            row.likedByViewer(),
            row.visibilityScope(),
            row.accuracyMeters(),
            row.placeId(),
            row.placeNameSnapshot(),
            row.placeResolutionStatus(),
            row.visibility(),
            row.moderationStatus(),
            row.publicationStatus()
        );
    }

    private URI thumbnailUrl(String thumbnailKey) {
        if (thumbnailKey == null || thumbnailKey.isBlank()) {
            return null;
        }
        GcsReadUrlSigner signer = signedReads.getIfAvailable();
        if (signer == null) {
            return null;
        }
        try {
            return signer.issueReadUrl(thumbnailKey);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private String landmarkImageUrl(CellRepository.SummaryRow row) {
        if (row.landmarkImageObjectKey() == null || row.landmarkImageObjectKey().isBlank()) {
            return row.landmarkImageUrl();
        }
        GcsReadUrlSigner signer = signedReads.getIfAvailable();
        if (signer == null) {
            return row.landmarkImageUrl();
        }
        try {
            return signer.issueReadUrl(row.landmarkImageObjectKey()).toString();
        } catch (RuntimeException error) {
            return row.landmarkImageUrl();
        }
    }

    private String background(CellRepository.SummaryRow row) {
        if (row.publicPhotoLikeCount() > 0) {
            return "hot";
        }
        if (row.myVisitCount() > 0) {
            return "visited";
        }
        return "empty";
    }

    private List<String> badges(CellRepository.SummaryRow row) {
        return row.landmarkCount() > 0 ? List.of("landmark") : List.of();
    }

    private List<String> visibilityReasons(CellRepository.VisibilityReasons reasons) {
        List<String> values = new ArrayList<>(5);
        if (reasons.licensedLandmark()) {
            values.add("licensed_landmark");
        }
        if (reasons.eligiblePublicPhoto()) {
            values.add("eligible_public_photo");
        }
        if (reasons.myVisit()) {
            values.add("my_visit");
        }
        if (reasons.myPhoto()) {
            values.add("my_photo");
        }
        if (reasons.authorizedGroupPhoto()) {
            values.add("authorized_group_photo");
        }
        return List.copyOf(values);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String cellId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Cell not found: " + cellId);
    }
}
