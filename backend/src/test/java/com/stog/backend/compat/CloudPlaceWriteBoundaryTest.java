package com.stog.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import java.lang.reflect.Method;
import org.springframework.transaction.annotation.Transactional;

public class CloudPlaceWriteBoundaryTest {
    @Test
    void writeRepositoryRequiresExplicitCloudWriteProfile() throws NoSuchMethodException {
        Profile profile = CloudPlaceWriteRepository.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("cloud-write");
        assertThat(CloudPlaceWriteRepository.class
            .getMethod("upsert", CloudPlaceUpsertCommand.class)
            .isAnnotationPresent(Transactional.class))
            .isTrue();
    }

    @Test
    void sourceRepositoryExposesOnlyReadOnlyTransactions() {
        for (Method method : CloudPlaceReadRepository.class.getDeclaredMethods()) {
            if (method.getName().equals("findActive")
                || method.getName().startsWith("count")) {
                assertThat(method.getAnnotation(Transactional.class)).isNotNull();
                assertThat(method.getAnnotation(Transactional.class).readOnly()).isTrue();
            }
        }
    }
}
