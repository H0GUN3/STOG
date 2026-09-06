package com.stog.backend.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthAccountRepository extends JpaRepository<AuthAccount, Long> {
    @Query(
        value = """
            SELECT *
            FROM auth_accounts
            WHERE provider = :provider
              AND provider_user_id = :providerUserId
            """,
        nativeQuery = true
    )
    Optional<AuthAccount> findByProviderAndProviderUserId(
        @Param("provider") String provider,
        @Param("providerUserId") String providerUserId
    );

    @Query(
        value = """
            SELECT EXISTS (
                SELECT 1
                FROM auth_accounts
                WHERE provider = :provider
                  AND provider_user_id = :providerUserId
            )
            """,
        nativeQuery = true
    )
    boolean existsByProviderAndProviderUserId(
        @Param("provider") String provider,
        @Param("providerUserId") String providerUserId
    );
}
