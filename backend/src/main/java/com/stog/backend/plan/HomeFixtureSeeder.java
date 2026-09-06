package com.stog.backend.plan;

import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@Profile("local")
@ConditionalOnProperty(name = "stog.home-fixtures.enabled", havingValue = "true")
class HomeFixtureSeeder implements ApplicationRunner {
    private static final String OWNER_NICKNAME = "윤건호";
    private static final Logger log = LoggerFactory.getLogger(HomeFixtureSeeder.class);

    private static final List<Fixture> FIXTURES = List.of(
        new Fixture(
            "여름 제주 여행 🌴",
            "active",
            LocalDate.of(2026, 7, 30),
            LocalDate.of(2026, 8, 2),
            List.of("신윤성", "서재철")
        ),
        new Fixture(
            "전주 한옥 여행",
            "dormant",
            LocalDate.of(2026, 8, 22),
            LocalDate.of(2026, 8, 24),
            List.of("신윤성")
        ),
        new Fixture(
            "부산 주말 여행",
            "dormant",
            LocalDate.of(2026, 9, 4),
            LocalDate.of(2026, 9, 6),
            List.of("서재철")
        ),
        new Fixture(
            "가을 남원 여행 🍁",
            "ended",
            LocalDate.of(2026, 10, 10),
            LocalDate.of(2026, 10, 12),
            List.of()
        )
    );

    private final JdbcClient jdbc;

    HomeFixtureSeeder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long ownerId = jdbc.sql("""
                SELECT id
                FROM users
                WHERE nickname = :nickname
                ORDER BY id
                LIMIT 1
                """)
            .param("nickname", OWNER_NICKNAME)
            .query(Long.class)
            .optional()
            .orElse(null);
        if (ownerId == null) {
            log.warn("Home fixtures skipped because the owner nickname was not found");
            return;
        }

        for (Fixture fixture : FIXTURES) {
            long tripId = findOrCreateTrip(ownerId, fixture);
            jdbc.sql("""
                    INSERT INTO trip_itinerary_states (trip_id)
                    VALUES (:tripId)
                    ON CONFLICT (trip_id) DO NOTHING
                    """)
                .param("tripId", tripId)
                .update();
            for (String nickname : fixture.memberNicknames()) {
                jdbc.sql("""
                        INSERT INTO trip_members (trip_id, user_id)
                        SELECT :tripId, member.id
                        FROM users member
                        WHERE member.nickname = :nickname
                          AND member.id <> :ownerId
                        ON CONFLICT (trip_id, user_id) DO NOTHING
                        """)
                    .param("tripId", tripId)
                    .param("nickname", nickname)
                    .param("ownerId", ownerId)
                    .update();
            }
        }
        log.info("Home fixtures ensured for {}: {} trips", OWNER_NICKNAME, FIXTURES.size());
    }

    private long findOrCreateTrip(long ownerId, Fixture fixture) {
        Long existingId = jdbc.sql("""
                SELECT id
                FROM trips
                WHERE owner_id = :ownerId
                  AND title = :title
                ORDER BY id
                LIMIT 1
                """)
            .param("ownerId", ownerId)
            .param("title", fixture.title())
            .query(Long.class)
            .optional()
            .orElse(null);
        if (existingId != null) {
            return existingId;
        }

        return jdbc.sql("""
                INSERT INTO trips (
                    owner_id, title, activity_type, mode,
                    planned_start_date, planned_end_date
                )
                VALUES (
                    :ownerId, :title, 'tour', :mode,
                    :startDate, :endDate
                )
                RETURNING id
                """)
            .param("ownerId", ownerId)
            .param("title", fixture.title())
            .param("mode", fixture.mode())
            .param("startDate", fixture.startDate())
            .param("endDate", fixture.endDate())
            .query(Long.class)
            .single();
    }

    private record Fixture(
        String title,
        String mode,
        LocalDate startDate,
        LocalDate endDate,
        List<String> memberNicknames
    ) {
    }
}
