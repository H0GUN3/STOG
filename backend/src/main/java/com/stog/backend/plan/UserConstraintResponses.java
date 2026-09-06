package com.stog.backend.plan;

import java.util.List;

public final class UserConstraintResponses {
    private UserConstraintResponses() {
    }

    public record Values(List<Value> constraints) {
        public Values {
            constraints = List.copyOf(constraints);
        }
    }

    public record Value(String constraint_type, String constraint_code) {
    }
}
