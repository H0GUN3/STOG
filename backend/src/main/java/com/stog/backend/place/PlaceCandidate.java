package com.stog.backend.place;

import java.util.List;

public record PlaceCandidate(
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
    String next_close_time
) {
    public PlaceCandidate(
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
        List<String> photo_names
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
            null,
            null,
            null,
            null,
            null
        );
    }

    public PlaceCandidate(
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
        String next_close_time
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
            next_close_time
        );
    }

    public PlaceCandidate {
        types = List.copyOf(types == null ? List.of() : types);
        regular_opening_hours = List.copyOf(
            regular_opening_hours == null ? List.of() : regular_opening_hours
        );
        photo_names = List.copyOf(photo_names == null ? List.of() : photo_names);
        photo_urls = List.copyOf(photo_urls == null ? List.of() : photo_urls);
    }
}
