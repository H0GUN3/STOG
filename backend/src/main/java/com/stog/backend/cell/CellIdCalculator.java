package com.stog.backend.cell;

import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;
import java.io.IOException;
import java.util.List;

public final class CellIdCalculator {
    public static final int RESOLUTION = 10;

    private static final H3Core H3 = createH3();

    private CellIdCalculator() {
    }

    public static long fromCoords(double latitude, double longitude) {
        if (!Double.isFinite(latitude)
            || latitude < -90.0
            || latitude > 90.0
            || !Double.isFinite(longitude)
            || longitude < -180.0
            || longitude > 180.0) {
            throw new IllegalArgumentException("latitude and longitude are invalid");
        }
        return H3.latLngToCell(latitude, longitude, RESOLUTION);
    }

    public static boolean isValidCell(long cellId) {
        try {
            return H3.isValidCell(cellId) && H3.getResolution(cellId) == RESOLUTION;
        } catch (RuntimeException error) {
            return false;
        }
    }

    public static String toWire(long cellId) {
        if (!isValidCell(cellId)) {
            throw new IllegalArgumentException("cell_id is invalid");
        }
        return H3.h3ToString(cellId);
    }

    public static CellResponses.Coordinate centroid(long cellId) {
        validateCell(cellId);
        LatLng center = H3.cellToLatLng(cellId);
        return new CellResponses.Coordinate(center.lat, center.lng);
    }

    public static List<CellResponses.Coordinate> boundary(long cellId) {
        validateCell(cellId);
        return H3.cellToBoundary(cellId).stream()
            .map(point -> new CellResponses.Coordinate(point.lat, point.lng))
            .toList();
    }

    public static long fromWire(String cellId) {
        if (cellId == null || !cellId.matches("^[0-9a-f]+$")) {
            throw new IllegalArgumentException("cell_id is invalid");
        }
        try {
            long parsed = H3.stringToH3(cellId);
            if (!H3.isValidCell(parsed)
                || H3.getResolution(parsed) != RESOLUTION
                || !H3.h3ToString(parsed).equals(cellId)) {
                throw new IllegalArgumentException("cell_id is invalid");
            }
            return parsed;
        } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException) {
                throw error;
            }
            throw new IllegalArgumentException("cell_id is invalid", error);
        }
    }

    private static void validateCell(long cellId) {
        if (!isValidCell(cellId)) {
            throw new IllegalArgumentException("cell_id is invalid");
        }
    }

    private static H3Core createH3() {
        try {
            return H3Core.newInstance();
        } catch (IOException error) {
            throw new IllegalStateException("H3 native library could not load", error);
        }
    }
}
