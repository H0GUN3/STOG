package com.stog.backend.auth;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/naver")
public class NaverOAuthController {
    private final OauthStateService states;
    private final NaverOAuthClient naver;
    private final LoginTicketService tickets;
    private final String appLinkUri;

    public NaverOAuthController(
        OauthStateService states,
        NaverOAuthClient naver,
        LoginTicketService tickets,
        @Value("${STOG_APP_LINK_URI:}") String appLinkUri
    ) {
        this.states = states;
        this.naver = naver;
        this.tickets = tickets;
        this.appLinkUri = appLinkUri;
    }

    @GetMapping("/start")
    public ResponseEntity<Void> start() {
        return ResponseEntity.status(302)
            .location(URI.create(naver.authorizeUri(states.issue())))
            .build();
    }

    @GetMapping("/callback")
    public ResponseEntity<?> callback(
        @RequestParam String code,
        @RequestParam String state
    ) {
        states.consume(state);
        SocialProfile profile = naver.profile(naver.exchangeCode(code, state));
        LoginTicketService.IssuedTicket ticket = tickets.issue(profile);
        if (appLinkUri.isBlank()) {
            return ResponseEntity.ok(ticket);
        }
        String separator = appLinkUri.contains("?") ? "&" : "?";
        return ResponseEntity.status(302)
            .location(URI.create(appLinkUri + separator + "ticket=" + ticket.value()))
            .build();
    }
}
