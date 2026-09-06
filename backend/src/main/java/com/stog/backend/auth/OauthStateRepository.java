package com.stog.backend.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface OauthStateRepository extends JpaRepository<OauthState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OauthState> findByStateHash(String stateHash);
}
