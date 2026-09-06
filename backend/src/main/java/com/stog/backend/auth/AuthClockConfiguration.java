package com.stog.backend.auth;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthClockConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
