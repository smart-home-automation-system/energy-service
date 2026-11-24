package cloud.cholewa.energy.frequency;

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

//application.yaml as the home profile reads it (HAS-503): the band of the grid frequency and its delays, a bare duration
//in minutes, and the bounds that refuse the start
class FrequencyPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_alert_outside_forty_nine_point_eight_and_fifty_point_two_hertz_after_two_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(FrequencyProperties.class))
                .isEqualTo(new FrequencyProperties(49.8, 50.2, Duration.ofMinutes(2), Duration.ofMinutes(5),
                    Duration.ofHours(1))));
    }

    @Test
    void should_read_a_bare_duration_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "energy.frequency.off-after=3",
                "energy.frequency.back-after=10", "energy.frequency.reminder-every=90")
            .run(context -> {
                final FrequencyProperties properties = context.getBean(FrequencyProperties.class);
                assertThat(properties.offAfter()).isEqualTo(Duration.ofMinutes(3));
                assertThat(properties.backAfter()).isEqualTo(Duration.ofMinutes(10));
                assertThat(properties.reminderEvery()).isEqualTo(Duration.ofMinutes(90));
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"energy.frequency.low=50.0", "energy.frequency.low=44.9", "energy.frequency.high=50.0",
        "energy.frequency.high=55.1", "energy.frequency.off-after=PT30S", "energy.frequency.off-after=61",
        "energy.frequency.back-after=0", "energy.frequency.reminder-every=9"})
    void should_not_start_with_a_value_out_of_its_range(final String value) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void should_hold_the_edges_inside_the_band() {
        final FrequencyProperties properties = new FrequencyProperties(49.8, 50.2, Duration.ofMinutes(2),
            Duration.ofMinutes(5), Duration.ofHours(1));
        assertThat(properties.off(49.8)).isFalse();
        assertThat(properties.off(50.2)).isFalse();
        assertThat(properties.off(49.79)).isTrue();
        assertThat(properties.off(51.0)).isTrue();
    }

    @Configuration
    @EnableConfigurationProperties(FrequencyProperties.class)
    static class Properties {
    }
}
