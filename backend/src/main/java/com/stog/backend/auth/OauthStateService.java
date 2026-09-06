package com.stog.backend.auth;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OauthStateService {
    private final OauthStateRepository states;
    private final TokenHasher hasher;
    private final RandomTokenGenerator generator;
    private final AuthProperties properties;
    private final Clock clock;

    public OauthStateService(
        OauthStateRepository states,
        TokenHasher hasher,
        RandomTokenGenerator generator,
        AuthProperties properties,
        Clock clock
    ) {
        this.states = states;
        this.hasher = hasher;
        this.generator = generator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public String issue() {
        String state = generator.generate();
        states.save(new OauthState(
            hasher.hash(state),
            clock.instant().plus(properties.naverTicketTtl())
        ));
        return state;
    }

    @Transactional
    public void consume(String state) {
        OauthState value = states.findByStateHash(hasher.hash(state))
            .filter(candidate -> candidate.isActive(clock.instant()))
            .orElseThrow(InvalidOauthStateException::new);
        value.consume(clock.instant());
        states.save(value);
    }
}
