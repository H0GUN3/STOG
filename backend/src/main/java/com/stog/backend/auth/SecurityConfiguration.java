package com.stog.backend.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sessions -> sessions.sessionCreationPolicy(
                SessionCreationPolicy.STATELESS
            ))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/error").permitAll()
                .requestMatchers("/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/.well-known/assetlinks.json").permitAll()
                .requestMatchers(HttpMethod.POST, "/places/search").permitAll()
                .requestMatchers(HttpMethod.POST, "/places/nearby").permitAll()
                .requestMatchers(HttpMethod.POST, "/events/nearby").permitAll()
                .requestMatchers(HttpMethod.GET, "/places/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/feed").permitAll()
                .requestMatchers(HttpMethod.GET, "/cells", "/cells/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {
            }))
            .build();
    }
}
