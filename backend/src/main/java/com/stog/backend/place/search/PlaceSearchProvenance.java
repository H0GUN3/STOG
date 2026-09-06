package com.stog.backend.place.search;

public record PlaceSearchProvenance(
    String kind,
    Long place_id,
    String source_type,
    Long source_id,
    String catalog_status
) {
    public static PlaceSearchProvenance canonical(
        long placeId,
        String sourceType,
        long sourceId,
        String catalogStatus
    ) {
        return new PlaceSearchProvenance(
            "canonical",
            placeId,
            sourceType,
            sourceId,
            catalogStatus
        );
    }

    public static PlaceSearchProvenance provider(String provider) {
        return new PlaceSearchProvenance("provider", null, provider, null, null);
    }
}
