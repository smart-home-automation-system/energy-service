package cloud.cholewa.energy.shedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

//application.yaml as the home profile reads it (HAS-523): ten minutes of the grid back end the load shedding; a bare
//number is minutes, and a time out of bounds refuses the start
class SheddingPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_end_after_ten_minutes_of_the_grid_back() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(SheddingProperties.class))
                .isEqualTo(new SheddingProperties(Duration.ofMinutes(10))));
    }

    @Test
    void should_read_a_bare_number_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "energy.shedding.stable-after=15")
            .run(context -> assertThat(context.getBean(SheddingProperties.class).stableAfter())
                .isEqualTo(Duration.ofMinutes(15)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"energy.shedding.stable-after=PT30S", "energy.shedding.stable-after=PT61M",
        "energy.shedding.stable-after="})
    void should_not_start_with_a_time_out_of_bounds(final String value) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(SheddingProperties.class)
    static class Properties {
    }
}
