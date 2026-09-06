package com.stog.backend.auth;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginTicketService {
    public record IssuedTicket(String value, Instant expiresAt) {
    }

    private final OauthLoginTicketRepository tickets;
    private final TokenHasher hasher;
    private final RandomTokenGenerator generator;
    private final AuthProperties properties;
    private final Clock clock;

    public LoginTicketService(
        OauthLoginTicketRepository tickets,
        TokenHasher hasher,
        RandomTokenGenerator generator,
        AuthProperties properties,
        Clock clock
    ) {
        this.tickets = tickets;
        this.hasher = hasher;
        this.generator = generator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public IssuedTicket issue(SocialProfile profile) {
        String value = generator.generate();
        Instant expiresAt = clock.instant().plus(properties.naverTicketTtl());
        tickets.save(new OauthLoginTicket(
            profile.provider(),
            profile.providerUserId(),
            profile.nickname(),
            hasher.hash(value),
            expiresAt
        ));
        return new IssuedTicket(value, expiresAt);
    }

    @Transactional
    public SocialProfile consume(String value) {
        OauthLoginTicket ticket = tickets.findByTokenHash(hasher.hash(value))
            .filter(candidate -> candidate.isActive(clock.instant()))
            .orElseThrow(InvalidLoginTicketException::new);
        ticket.consume(clock.instant());
        tickets.save(ticket);
        return ticket.profile();
    }
}
