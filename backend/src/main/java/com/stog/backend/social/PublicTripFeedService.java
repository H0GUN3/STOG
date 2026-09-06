package com.stog.backend.social;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PublicTripFeedService {
    private final PublicTripRepository trips;
    private final FeedProperties properties;

    public PublicTripFeedService(PublicTripRepository trips, FeedProperties properties) {
        this.trips = trips;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public PublicTripResponses.Page get(Long viewerId, String cursorValue, Integer requestedLimit) {
        PublicTripCursor cursor = cursorValue == null ? null : PublicTripCursor.decode(cursorValue);
        int limit = pageSize(requestedLimit);
        List<PublicTripRepository.TripRow> rows = trips.findTrips(viewerId, cursor, limit + 1);
        boolean hasNext = rows.size() > limit;
        List<PublicTripRepository.TripRow> page = hasNext ? rows.subList(0, limit) : rows;
        Map<Long, List<PublicTripRepository.VisitRow>> visitsByTrip = trips.findVisitsByTripIds(
            page.stream().map(PublicTripRepository.TripRow::id).toList()
        );
        String next = hasNext ? new PublicTripCursor(
            page.get(page.size() - 1).endedAt(), page.get(page.size() - 1).id()
        ).encode() : null;
        return new PublicTripResponses.Page(
            page.stream().map(trip -> item(
                trip, visitsByTrip.getOrDefault(trip.id(), List.of())
            )).toList(),
            next
        );
    }

    private PublicTripResponses.Item item(
        PublicTripRepository.TripRow trip,
        List<PublicTripRepository.VisitRow> visits
    ) {
        Map<Long, List<PublicTripResponses.Visit>> byUser = new LinkedHashMap<>();
        for (PublicTripRepository.VisitRow visit : visits) {
            byUser.computeIfAbsent(visit.userId(), ignored -> new ArrayList<>()).add(
                new PublicTripResponses.Visit(
                    visit.id(), visit.cellId(), visit.lat(), visit.lng(), visit.enteredAt(),
                    visit.leftAt(), visit.status(), visit.interpolated()
                )
            );
        }
        List<PublicTripResponses.MemberTrail> trails = byUser.entrySet().stream()
            .map(entry -> new PublicTripResponses.MemberTrail(entry.getKey(), entry.getValue()))
            .toList();
        return new PublicTripResponses.Item(
            trip.id(), trip.ownerId(), trip.title(), trip.endedAt(), trip.likeCount(),
            trip.likedByViewer(), trip.itineraryItemCount(), trails
        );
    }

    private int pageSize(Integer requested) {
        if (requested == null) return properties.pageSize();
        if (requested < 1 || requested > properties.pageSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Public trip limit is invalid");
        }
        return requested;
    }
}
