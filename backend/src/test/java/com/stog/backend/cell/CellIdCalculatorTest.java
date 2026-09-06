package com.stog.backend.cell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uber.h3core.H3Core;
import org.junit.jupiter.api.Test;

class CellIdCalculatorTest {
    private static final H3Core H3 = createH3();

    @Test
    void coordinateConversionUsesCanonicalResolutionTen() {
        long cellId = CellIdCalculator.fromCoords(35.815, 127.15);

        assertThat(H3.isValidCell(cellId)).isTrue();
        assertThat(H3.getResolution(cellId)).isEqualTo(10);
        assertThat(CellIdCalculator.toWire(cellId))
            .isEqualTo(H3.h3ToString(cellId));
    }

    @Test
    void returnsTheCanonicalH3CentroidAndBoundaryForAResolutionTenCell() {
        long cellId = CellIdCalculator.fromCoords(35.815, 127.15);

        CellResponses.Coordinate centroid = CellIdCalculator.centroid(cellId);
        assertThat(centroid.lat()).isCloseTo(35.815, org.assertj.core.data.Offset.offset(0.01));
        assertThat(centroid.lng()).isCloseTo(127.15, org.assertj.core.data.Offset.offset(0.01));
        assertThat(CellIdCalculator.boundary(cellId))
            .hasSize(6)
            .allSatisfy(point -> {
                assertThat(point.lat()).isBetween(-90.0, 90.0);
                assertThat(point.lng()).isBetween(-180.0, 180.0);
            });
    }

    @Test
    void rejectsStructurallyInvalidCellWithResolutionTenBits() {
        long invalidCell = 621496748577128448L;

        assertThat(H3.getResolution(invalidCell)).isEqualTo(10);
        assertThat(CellIdCalculator.isValidCell(invalidCell)).isFalse();
        assertThatThrownBy(() -> CellIdCalculator.toWire(invalidCell))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CellIdCalculator.fromWire("8a0000000000000"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coordinateConversionRejectsOutOfRangeCoordinates() {
        assertThatThrownBy(() -> CellIdCalculator.fromCoords(91.0, 127.15))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CellIdCalculator.fromCoords(35.815, -181.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coordinateConversionRejectsNonFiniteCoordinates() {
        assertThatThrownBy(() -> CellIdCalculator.fromCoords(Double.NaN, 127.15))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CellIdCalculator.fromCoords(35.815, Double.POSITIVE_INFINITY))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static H3Core createH3() {
        try {
            return H3Core.newInstance();
        } catch (Exception error) {
            throw new AssertionError("H3 native library could not load", error);
        }
    }
}
