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

//application.yaml as the home profile reads it (HAS-495): a battery 5 % below its reserve for half an hour is a
//warning, reminded every hour; a bare duration in minutes and the bounds that refuse the start
class ReserveSocPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_take_five_percent_warn_after_half_an_hour_and_remind_every_hour() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(ReserveSocProperties.class))
                .isEqualTo(new ReserveSocProperties(5, Duration.ofMinutes(30), Duration.ofHours(1))));
    }

    @Test
    void should_read_a_bare_duration_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "energy.charge.soc.warn-after=45",
                "energy.charge.soc.reminder-every=90")
            .run(context -> assertThat(context.getBean(ReserveSocProperties.class))
                .isEqualTo(new ReserveSocProperties(5, Duration.ofMinutes(45), Duration.ofMinutes(90))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"energy.charge.soc.tolerance=0", "energy.charge.soc.tolerance=21",
        "energy.charge.soc.tolerance=", "energy.charge.soc.warn-after=9", "energy.charge.soc.warn-after=PT7H",
        "energy.charge.soc.warn-after=", "energy.charge.soc.reminder-every=9",
        "energy.charge.soc.reminder-every=PT25H"})
    void should_not_start_with_a_value_out_of_its_range(final String value) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(ReserveSocProperties.class)
    static class Properties {
    }
}
