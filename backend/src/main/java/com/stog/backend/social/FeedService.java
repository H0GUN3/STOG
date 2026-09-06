package com.stog.backend.social;

import com.stog.backend.cell.CellIdCalculator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FeedService {
    private final FeedRepository feed;
    private final FeedProperties properties;
    private final FeedThumbnailMediaResolver thumbnailMedia;

    public FeedService(
        FeedRepository feed,
        FeedProperties properties,
        FeedThumbnailMediaResolver thumbnailMedia
    ) {
        this.feed = feed;
        this.properties = properties;
        this.thumbnailMedia = thumbnailMedia;
    }

    @Transactional(readOnly = true)
    public FeedResponses.Page get(Long viewerId, String cursorValue, Integer requestedLimit) {
        return get(viewerId, cursorValue, requestedLimit, false);
    }

    @Transactional(readOnly = true)
    public FeedResponses.Page getSaved(long viewerId, String cursorValue, Integer requestedLimit) {
        return get(viewerId, cursorValue, requestedLimit, true);
    }

    private FeedResponses.Page get(
        Long viewerId,
        String cursorValue,
        Integer requestedLimit,
        boolean savedOnly
    ) {
        FeedCursor cursor = cursorValue == null ? null : FeedCursor.decode(cursorValue);
        int limit = pageSize(requestedLimit);
        List<FeedRepository.FeedRow> rows = savedOnly
            ? feed.findSaved(viewerId, cursor, limit + 1)
            : feed.find(viewerId, cursor, limit + 1);
        boolean hasNext = rows.size() > limit;
        List<FeedRepository.FeedRow> page = hasNext ? rows.subList(0, limit) : rows;
        String nextCursor = hasNext
            ? cursorFor(page.get(page.size() - 1))
            : null;
        return new FeedResponses.Page(page.stream().map(this::response).toList(), nextCursor);
    }

    private int pageSize(Integer requestedLimit) {
        if (requestedLimit == null) {
            return properties.pageSize();
        }
        if (requestedLimit < 1 || requestedLimit > properties.pageSize()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Feed limit exceeds the configured page size"
            );
        }
        return requestedLimit;
    }

    private String cursorFor(FeedRepository.FeedRow row) {
        return new FeedCursor(row.cursorCreatedAt(), row.photoId()).encode();
    }

    private FeedResponses.Item response(FeedRepository.FeedRow row) {
        return new FeedResponses.Item(
            row.photoId(),
            row.tripId(),
            row.ownerId(),
            row.ownerNickname(),
            thumbnailMedia.resolveProfileImage(row.ownerProfileImageKey()),
            row.thumbnailKey(),
            thumbnailMedia.resolve(row.signedReadEligible(), row.thumbnailKey()),
            row.caption(),
            row.cellId() == null ? null : CellIdCalculator.toWire(row.cellId()),
            row.latitude(),
            row.longitude(),
            row.takenAt(),
            row.likeCount(),
            row.commentCount(),
            row.likedByViewer(),
            row.createdAt(),
            row.savedByViewer()
        );
    }
}
