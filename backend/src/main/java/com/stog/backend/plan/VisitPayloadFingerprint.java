package com.stog.backend.plan;

import com.stog.backend.cell.CellIdCalculator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class VisitPayloadFingerprint {
    private VisitPayloadFingerprint() {
    }

    static String compute(
        long cellId,
        VisitRequests.Record request,
        VisitDecision.Result decision
    ) {
        String canonical = "cell_id=" + CellIdCalculator.toWire(cellId)
            + "\nlat=" + decimal(request.lat())
            + "\nlng=" + decimal(request.lng())
            + "\nentered_at=" + request.entered_at()
            + "\nleft_at=" + request.left_at()
            + "\nstatus=" + decision.status().value()
            + "\nis_interpolated=" + request.is_interpolated();
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    canonical.getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
