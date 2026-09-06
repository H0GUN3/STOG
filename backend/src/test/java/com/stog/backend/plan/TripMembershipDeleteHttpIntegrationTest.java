package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.stog.backend.auth.AccessTokenService;
import com.stog.backend.auth.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TripMembershipDeleteHttpIntegrationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlanningService planning;

    @Autowired
    private TripMembershipService memberships;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccessTokenService accessTokens;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @AfterEach
    void cleanup() {
        jdbc.sql(
                """
                DELETE FROM trips
                WHERE owner_id IN (
                    SELECT id FROM users WHERE nickname LIKE 'todo4-delete-http-%'
                )
                """
            )
            .update();
        jdbc.sql("DELETE FROM users WHERE nickname LIKE 'todo4-delete-http-%'").update();
    }

    @Test
    void deleteRequiresOwnerAndNoActiveMembers() throws Exception {
        long ownerId = user("owner");
        long memberId = user("member");
        long tripId = planning.createTrip(
            ownerId,
            new TripRequests.Create("HTTP delete trip", "tour", null, null)
        ).id();
        TripMembershipResponses.InviteLink invite = memberships.createInvite(ownerId, tripId);
        memberships.join(memberId, invite.token());

        HttpResponse<String> ownerBlocked = delete(token(ownerId), tripId);
        HttpResponse<String> memberDenied = delete(token(memberId), tripId);

        assertThat(ownerBlocked.statusCode()).isEqualTo(400);
        assertThat(memberDenied.statusCode()).isEqualTo(403);

        memberships.leave(memberId, tripId);
        HttpResponse<String> ownerDeleted = delete(token(ownerId), tripId);

        assertThat(ownerDeleted.statusCode()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trips WHERE id = :tripId")
            .param("tripId", tripId)
            .query(Long.class)
            .single()).isZero();
    }

    private HttpResponse<String> delete(String token, long tripId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/trips/" + tripId))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + token)
            .DELETE()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String token(long userId) {
        return accessTokens.issue(users.findById(userId).orElseThrow());
    }

    private long user(String suffix) {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", "todo4-delete-http-" + suffix + "-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    }
}
