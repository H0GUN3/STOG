package com.stog.backend.place.search;

import com.stog.backend.place.PlaceCandidate;
import java.util.List;

public record PlaceSearchResult(
    String provider,
    String external_id,
    String name,
    String formatted_address,
    Double latitude,
    Double longitude,
    List<String> types,
    List<String> regular_opening_hours,
    String national_phone_number,
    String website_uri,
    String google_maps_uri,
    List<String> photo_names,
    List<String> photo_urls,
    Double rating,
    Integer user_rating_count,
    String business_status,
    Boolean open_now,
    String next_close_time,
    PlaceSearchProvenance provenance
) {
    public PlaceSearchResult {
        types = List.copyOf(types == null ? List.of() : types);
        regular_opening_hours = List.copyOf(
            regular_opening_hours == null ? List.of() : regular_opening_hours
        );
        photo_names = List.copyOf(photo_names == null ? List.of() : photo_names);
        photo_urls = List.copyOf(photo_urls == null ? List.of() : photo_urls);
    }

    public PlaceSearchResult(
        String provider,
        String external_id,
        String name,
        String formatted_address,
        Double latitude,
        Double longitude,
        List<String> types,
        List<String> regular_opening_hours,
        String national_phone_number,
        String website_uri,
        String google_maps_uri,
        List<String> photo_names,
        Double rating,
        Integer user_rating_count,
        String business_status,
        Boolean open_now,
        String next_close_time,
        PlaceSearchProvenance provenance
    ) {
        this(
            provider,
            external_id,
            name,
            formatted_address,
            latitude,
            longitude,
            types,
            regular_opening_hours,
            national_phone_number,
            website_uri,
            google_maps_uri,
            photo_names,
            List.of(),
            rating,
            user_rating_count,
            business_status,
            open_now,
            next_close_time,
            provenance
        );
    }

    public static PlaceSearchResult provider(PlaceCandidate candidate) {
        return new PlaceSearchResult(
            candidate.provider(),
            candidate.external_id(),
            candidate.name(),
            candidate.formatted_address(),
            candidate.latitude(),
            candidate.longitude(),
            candidate.types(),
            candidate.regular_opening_hours(),
            candidate.national_phone_number(),
            candidate.website_uri(),
            candidate.google_maps_uri(),
            candidate.photo_names(),
            candidate.photo_urls(),
            candidate.rating(),
            candidate.user_rating_count(),
            candidate.business_status(),
            candidate.open_now(),
            candidate.next_close_time(),
            PlaceSearchProvenance.provider(candidate.provider())
        );
    }
}
