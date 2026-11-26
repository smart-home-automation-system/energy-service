package cloud.cholewa.energy.solax;

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

//application.yaml as the home profile reads it (HAS-518): the settings of the SOLAX every five minutes and twenty
//seconds after a write, not known after a quarter of an hour; the units of a bare number and the bounds that refuse the
//start - stale-after below twice the interval among them (one failed read would make the settings unknown)
class SolaxSettingsPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_read_every_five_minutes_and_twenty_seconds_after_a_write() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(SolaxSettingsProperties.class))
                .isEqualTo(new SolaxSettingsProperties(Duration.ofMinutes(5), Duration.ofSeconds(20),
                    Duration.ofMinutes(15))));
    }

    @Test
    void should_read_a_bare_interval_as_minutes_and_a_bare_after_write_as_seconds() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "energy.solax.settings.interval=10",
                "energy.solax.settings.after-write=30", "energy.solax.settings.stale-after=45")
            .run(context -> assertThat(context.getBean(SolaxSettingsProperties.class))
                .isEqualTo(new SolaxSettingsProperties(Duration.ofMinutes(10), Duration.ofSeconds(30),
                    Duration.ofMinutes(45))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"energy.solax.settings.interval=PT30S", "energy.solax.settings.interval=61",
        "energy.solax.settings.interval=", "energy.solax.settings.after-write=4",
        "energy.solax.settings.after-write=121", "energy.solax.settings.stale-after=4",
        "energy.solax.settings.stale-after=121", "energy.solax.settings.stale-after=9",
        "energy.solax.settings.interval=8"})
    void should_not_start_with_a_value_out_of_its_range(final String value) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(SolaxSettingsProperties.class)
    static class Properties {
    }
}
