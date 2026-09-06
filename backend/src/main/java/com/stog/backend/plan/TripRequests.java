package com.stog.backend.plan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

public final class TripRequests {
    private TripRequests() {
    }

    public record Create(
        @NotBlank String title,
        @NotBlank String activity_type,
        LocalDate planned_start_date,
        LocalDate planned_end_date,
        @Pattern(regexp = "^(JEONBUK|JEONJU|GUNSAN|IKSAN|JEONGEUP|NAMWON|GIMJE|WANJU|JINAN|MUJU|JANGSU|IMSIL|SUNCHANG|GOCHANG|BUAN)$")
        String region_code
    ) {
        public Create(
            String title,
            String activity_type,
            LocalDate planned_start_date,
            LocalDate planned_end_date
        ) {
            this(title, activity_type, planned_start_date, planned_end_date, "JEONBUK");
        }
    }

    public record Update(
        @NotBlank String title,
        LocalDate planned_start_date,
        LocalDate planned_end_date
    ) {
    }

    public record Mode(
        @NotBlank @Pattern(regexp = "^(active|dormant|ended)$") String mode
    ) {
    }
}
