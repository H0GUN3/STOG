package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.stog.backend.trail.TrailResult;
import com.stog.backend.profile.ProfileService;
import com.stog.backend.profile.ProfileResponses;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class TravelGuideAiConcurrentApplyTest {
    private DataSource baseDataSource;

    private TravelGuideAiPreviewService previews;
    private TravelGuideAiApplyService applies;
    private TransactionTemplate transactions;
    private JdbcClient jdbc;
    private String schema;
    private long userId;
    private long tripId;
    private long basketItemId;

    @BeforeEach
    void createFixture() throws Exception {
        baseDataSource = Task14TestDataSource.create();
        schema = "task14_concurrent_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = baseDataSource.getConnection()) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure().dataSource(baseDataSource).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").load().migrate();
        DataSource scopedDataSource = new SchemaScopedDataSource(baseDataSource, schema);
        jdbc = JdbcClient.create(scopedDataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(scopedDataSource));
        ItineraryRepository itineraries = new ItineraryRepository(jdbc);
        TravelGuideAiProposalRepository proposals = new TravelGuideAiProposalRepository(jdbc);
        TripMembershipPolicy memberships = new TripMembershipPolicy(
            new TripMembershipRepository(jdbc)
        );
        ProfileService profiles = new ProfileService(null, null, null, null, null) {
            @Override
            public ProfileResponses.Profile get(long ignoredUserId) {
                return new ProfileResponses.Profile(
                    java.util.Map.of(
                        "nature", 0.0,
                        "culture", 0.0,
                        "food", 0.0,
                        "shopping", 0.0,
                        "experience", 1.0,
                        "relaxation", 0.0
                    ),
                    java.util.Map.of()
                );
            }
        };
        previews = new TravelGuideAiPreviewService(
            memberships, itineraries, proposals, request -> new TrailResult("0s", 0L, ""),
            profiles
        );
        applies = new TravelGuideAiApplyService(memberships, itineraries, proposals);
        TravelGuideAiTestFixture.ConcurrentFixture fixture = transactions.execute(
            status -> new TravelGuideAiTestFixture(jdbc).createConcurrent()
        );
        userId = fixture.ownerId();
        tripId = fixture.tripId();
        basketItemId = fixture.basketItemId();
    }

    @AfterEach
    void removeFixture() throws SQLException {
        try (Connection connection = baseDataSource.getConnection()) {
            connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void identicalConcurrentApplyCreatesOneItineraryChange() throws Exception {
        // Given
        TravelGuideAiResponses.Preview preview = transactions.execute(status -> previews.preview(
            userId,
            tripId,
            new TravelGuideAiRequests.Preview(
                0,
                List.of(new TravelGuideAiRequests.DayWindow(1, "09:00", "18:00")),
                List.of(new TravelGuideAiRequests.Action(
                    basketItemId, 1, 0, "09:00", 60, 0, false
                ))
            )
        ));
        TravelGuideAiRequests.Apply request = new TravelGuideAiRequests.Apply(
            UUID.randomUUID(), preview.proposal_fingerprint()
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ApplyAttempt attempt = new ApplyAttempt(ready, start, preview.suggestion_id(), request);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // When
        try {
            Future<TravelGuideAiResponses.Applied> first = executor.submit(
                () -> applyAfterSignal(attempt)
            );
            Future<TravelGuideAiResponses.Applied> second = executor.submit(
                () -> applyAfterSignal(attempt)
            );
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            TravelGuideAiResponses.Applied firstResult = first.get(10, TimeUnit.SECONDS);
            TravelGuideAiResponses.Applied secondResult = second.get(10, TimeUnit.SECONDS);

            // Then
            assertThat(secondResult).isEqualTo(firstResult);
            assertThat(count("itinerary_changes")).isOne();
            assertThat(count("itinerary_items")).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    private TravelGuideAiResponses.Applied applyAfterSignal(
        ApplyAttempt attempt
    ) throws InterruptedException {
        attempt.ready().countDown();
        if (!attempt.start().await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent apply start signal timed out");
        }
        return transactions.execute(status -> applies.apply(
            userId,
            new TravelGuideAiApplyService.ProposalScope(tripId, attempt.suggestionId()),
            attempt.request()
        ));
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE trip_id = :tripId")
            .param("tripId", tripId)
            .query(Long.class)
            .single();
    }

    private record ApplyAttempt(
        CountDownLatch ready,
        CountDownLatch start,
        UUID suggestionId,
        TravelGuideAiRequests.Apply request
    ) {
    }

}
