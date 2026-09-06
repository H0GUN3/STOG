package com.stog.backend.auth;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class RandomTokenGenerator {
    private final SecureRandom random = new SecureRandom();
    private final AuthProperties properties;

    public RandomTokenGenerator(AuthProperties properties) {
        this.properties = properties;
    }

    public String generate() {
        byte[] bytes = new byte[properties.randomTokenBytes()];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
