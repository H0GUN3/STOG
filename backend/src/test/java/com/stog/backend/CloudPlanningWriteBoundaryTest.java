package com.stog.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.stog.backend.place.PlaceController;
import com.stog.backend.plan.BasketItemController;
import com.stog.backend.plan.TripController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class CloudPlanningWriteBoundaryTest {
    @Test
    void cloudProfileDoesNotExposePlanningWriteControllers() {
        try (AnnotationConfigWebApplicationContext context =
                 new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().setActiveProfiles("cloud");
            context.register(
                WebConfiguration.class,
                TripController.class,
                PlaceController.class,
                BasketItemController.class
            );
            context.refresh();

            assertThat(context.getBeansOfType(TripController.class)).isEmpty();
            assertThat(context.getBeansOfType(PlaceController.class)).isEmpty();
            assertThat(context.getBeansOfType(BasketItemController.class)).isEmpty();
            assertThat(context.getBean(RequestMappingHandlerMapping.class)
                .getHandlerMethods().values())
                .noneMatch(handler -> handler.getBeanType() == TripController.class
                    || handler.getBeanType() == PlaceController.class
                    || handler.getBeanType() == BasketItemController.class);
        }
    }

    @Configuration
    @EnableWebMvc
    static class WebConfiguration {
    }
}
