package cloud.cholewa.energy.charge;

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

//application.yaml as the home profile reads it (HAS-518): a reserve not held for ten minutes is a warning, reminded
//every hour; a bare duration in minutes and the bounds that refuse the start
class ReserveFloorPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_warn_after_ten_minutes_and_remind_every_hour() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(ReserveFloorProperties.class))
                .isEqualTo(new ReserveFloorProperties(Duration.ofMinutes(10), Duration.ofHours(1))));
    }

    @Test
    void should_read_a_bare_duration_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "energy.charge.floor.warn-after=15",
                "energy.charge.floor.reminder-every=90")
            .run(context -> assertThat(context.getBean(ReserveFloorProperties.class))
                .isEqualTo(new ReserveFloorProperties(Duration.ofMinutes(15), Duration.ofMinutes(90))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"energy.charge.floor.warn-after=1", "energy.charge.floor.warn-after=61",
        "energy.charge.floor.warn-after=", "energy.charge.floor.reminder-every=9",
        "energy.charge.floor.reminder-every=PT25H"})
    void should_not_start_with_a_value_out_of_its_range(final String value) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(ReserveFloorProperties.class)
    static class Properties {
    }
}
