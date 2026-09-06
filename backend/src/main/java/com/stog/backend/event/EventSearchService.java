package com.stog.backend.event;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventSearchService {
    private final EventRepository repository;
    private final EventSearchProperties properties;

    public EventSearchService(
        EventRepository repository,
        EventSearchProperties properties
    ) {
        this.repository = repository;
        this.properties = properties;
    }

    public EventSearchResponse nearby(EventRequests.Nearby request) {
        double radiusMeters = request.radius_meters() == null
            ? properties.defaultRadiusMeters()
            : request.radius_meters();
        LocalDate fromDate = request.from_date() == null
            ? LocalDate.now(ZoneOffset.UTC)
            : request.from_date();
        LocalDate toDate = request.to_date() == null
            ? fromDate.plusDays(properties.horizonDays())
            : request.to_date();
        if (!Double.isFinite(radiusMeters) || radiusMeters <= 0.0) {
            throw badRequest("radius_meters must be positive and finite");
        }
        if (toDate.isBefore(fromDate)) {
            throw badRequest("to_date must not be before from_date");
        }
        int limit = request.max_result_count() == null
            ? properties.resultLimit()
            : request.max_result_count();
        List<EventSearchResult> events = repository.nearby(
            request.center().latitude(),
            request.center().longitude(),
            radiusMeters,
            fromDate,
            toDate,
                limit
            ).stream()
            .map(EventRepository.EventRow::toSearchResult)
            .toList();
        return new EventSearchResponse(events);
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
