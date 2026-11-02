package cloud.cholewa.energy.production;

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

//application.yaml as the home profile reads it (HAS-333): the thresholds of the production watch, a bare duration in
//minutes, and the bounds that refuse the start
class ProductionPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class)
        .withPropertyValues("location-latitude=50.0", "location-longitude=19.0");

    @Test
    void should_alert_after_a_quarter_of_an_hour_of_sun_without_production() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(ProductionProperties.class))
                .isEqualTo(new ProductionProperties(Duration.ofMinutes(15), 20000, 100, Duration.ofHours(1))));
    }

    @Test
    void should_read_a_bare_duration_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "energy.production.silence=20",
                "energy.production.reminder-every=90")
            .run(context -> {
                final ProductionProperties properties = context.getBean(ProductionProperties.class);
                assertThat(properties.silence()).isEqualTo(Duration.ofMinutes(20));
                assertThat(properties.reminderEvery()).isEqualTo(Duration.ofMinutes(90));
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"energy.production.silence=4", "energy.production.silence=PT2H1M",
        "energy.production.illuminance-threshold=999", "energy.production.min-power=9",
        "energy.production.reminder-every=9"})
    void should_not_start_with_a_value_out_of_its_range(final String value) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties({ProductionProperties.class, LocationProperties.class})
    static class Properties {
    }
}
