package com.stog.backend.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public final class UserConstraintRequests {
    private UserConstraintRequests() {
    }

    public record Replace(@NotNull List<@Valid Item> constraints) {
        public Replace {
            constraints = List.copyOf(constraints);
        }
    }

    public record Item(
        @NotBlank String constraint_type,
        @NotBlank String constraint_code
    ) {
    }
}
