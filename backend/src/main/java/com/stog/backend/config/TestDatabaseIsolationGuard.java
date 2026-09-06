package com.stog.backend.config;

import java.util.Collection;

public final class TestDatabaseIsolationGuard {
    public static final String REJECTION_CODE = "STOG_TEST_DATABASE_ISOLATION_REJECTED";
    public static final String APPROVED_URL =
        "jdbc:postgresql://127.0.0.1:5432/stog_backend_test";
    public static final String APPROVED_USERNAME = "stog_backend_test";

    private TestDatabaseIsolationGuard() {
    }

    public static void validate(
        Collection<String> activeProfiles,
        String datasourceUrl,
        String datasourceUsername,
        String flywayUrl,
        String flywayUsername
    ) {
        if (activeProfiles == null
            || activeProfiles.size() != 1
            || !"test".equals(activeProfiles.iterator().next())) {
            throw new Rejected("active profiles must be exactly [test]");
        }
        requireExact("runtime datasource URL", datasourceUrl, APPROVED_URL);
        requireExact("runtime datasource identity", datasourceUsername, APPROVED_USERNAME);
        requireExact("Flyway URL", flywayUrl, APPROVED_URL);
        requireExact("Flyway identity", flywayUsername, APPROVED_USERNAME);
    }

    private static void requireExact(String input, String actual, String approved) {
        if (!approved.equals(actual)) {
            throw new Rejected(input + " is not the approved test-only value");
        }
    }

    public static final class Rejected extends IllegalStateException {
        public Rejected(String reason) {
            super(REJECTION_CODE + ": " + reason);
        }

        public String code() {
            return REJECTION_CODE;
        }
    }
}
