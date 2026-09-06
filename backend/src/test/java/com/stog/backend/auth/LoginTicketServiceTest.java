package com.stog.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class LoginTicketServiceTest {
    @Autowired
    private LoginTicketService tickets;

    @Test
    void ticketIsFiveMinutesAndOneTime() {
        SocialProfile profile = new SocialProfile("naver", "naver-user", "네이버 사용자");

        LoginTicketService.IssuedTicket issued = tickets.issue(profile);
        SocialProfile consumed = tickets.consume(issued.value());

        assertThat(consumed).isEqualTo(profile);
        assertThat(issued.expiresAt()).isAfter(java.time.Instant.now().plusSeconds(299));
        assertThatThrownBy(() -> tickets.consume(issued.value()))
            .isInstanceOf(InvalidLoginTicketException.class);
    }
}
