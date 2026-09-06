package com.stog.backend.google;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.ExceptionHandler;

class GoogleProviderErrorHandlerTest {
    @Test
    void handlesOnlyGoogleProviderExceptions() {
        Class<?>[] handledTypes = Arrays.stream(
                GoogleProviderErrorHandler.class.getDeclaredMethods()
            )
            .map(method -> method.getAnnotation(ExceptionHandler.class))
            .filter(annotation -> annotation != null)
            .flatMap(annotation -> Arrays.stream(annotation.value()))
            .toArray(Class<?>[]::new);

        assertThat(handledTypes).containsExactly(GoogleProviderException.class);
    }
}
