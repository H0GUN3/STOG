package com.stog.backend.compat;

import com.stog.backend.place.PlaceCandidate;
import java.util.List;
import java.util.Locale;

public final class CloudPlaceMapper {
    public CloudPlaceMapping map(CloudPlaceRow row) {
        String provider = row.externalProvider() == null
            ? providerFromSource(row.source())
            : providerFromExternal(row.externalProvider());
        String externalId = row.externalId() == null
            ? row.sourceItemId()
            : row.externalId();
        List<String> types = row.itemType() == null
            ? List.of()
            : List.of(row.itemType().toLowerCase(Locale.ROOT));

        return new CloudPlaceMapping(
            row.travelItemId(),
            new PlaceCandidate(
                provider,
                externalId,
                row.name(),
                row.address(),
                row.latitude(),
                row.longitude(),
                types,
                List.of(),
                null,
                null,
                null,
                List.of()
            ),
            row.source(),
            row.sourceItemId(),
            row.legacyCellId(),
            row.legacyH3Index(),
            row.legacyH3Resolution()
        );
    }

    private String providerFromExternal(String provider) {
        return switch (provider.toUpperCase(Locale.ROOT)) {
            case "GOOGLE" -> "google";
            case "KAKAO" -> "kakao";
            default -> throw new IllegalArgumentException(
                "provider is unsupported: " + provider
            );
        };
    }

    private String providerFromSource(String source) {
        return switch (source) {
            case "KAKAO_LOCAL" -> "kakao";
            case "TOUR_API", "AREA_RESTAURANT" -> "public_data";
            default -> throw new IllegalArgumentException(
                "source is unsupported: " + source
            );
        };
    }
}
