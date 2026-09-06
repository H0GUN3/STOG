package com.stog.backend.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface OauthLoginTicketRepository
    extends JpaRepository<OauthLoginTicket, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OauthLoginTicket> findByTokenHash(String tokenHash);
}
