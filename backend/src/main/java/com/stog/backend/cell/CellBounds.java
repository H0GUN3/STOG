package com.stog.backend.cell;

/** A non-empty, non-antimeridian viewport supplied by the Cell API caller. */
public record CellBounds(double swLat, double swLng, double neLat, double neLng) {
    public static CellBounds of(Double swLat, Double swLng, Double neLat, Double neLng) {
        if (swLat == null || swLng == null || neLat == null || neLng == null) {
            throw new IllegalArgumentException("Cell bounds are required");
        }
        if (!isLatitude(swLat) || !isLatitude(neLat)
            || !isLongitude(swLng) || !isLongitude(neLng)) {
            throw new IllegalArgumentException("Cell bounds are outside coordinate ranges");
        }
        if (swLat >= neLat || swLng >= neLng) {
            throw new IllegalArgumentException("Cell bounds must describe a non-empty viewport");
        }
        return new CellBounds(swLat, swLng, neLat, neLng);
    }

    private static boolean isLatitude(double value) {
        return Double.isFinite(value) && value >= -90.0 && value <= 90.0;
    }

    private static boolean isLongitude(double value) {
        return Double.isFinite(value) && value >= -180.0 && value <= 180.0;
    }
}
