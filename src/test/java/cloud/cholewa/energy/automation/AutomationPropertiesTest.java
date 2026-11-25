package cloud.cholewa.energy.automation;

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

//application.yaml as the home profile reads it (HAS-478): three runs an outage at most, five minutes after a failed one;
//a bare number of the pause is minutes, and attempts or a pause out of bounds refuse the start
class AutomationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_try_three_times_five_minutes_apart() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(AutomationProperties.class))
                .isEqualTo(new AutomationProperties(3, Duration.ofMinutes(5))));
    }

    @Test
    void should_read_a_bare_number_of_the_pause_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "energy.automation.retry-every=10")
            .run(context -> assertThat(context.getBean(AutomationProperties.class).retryEvery())
                .isEqualTo(Duration.ofMinutes(10)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"energy.automation.max-attempts=0", "energy.automation.max-attempts=6",
        "energy.automation.max-attempts=", "energy.automation.retry-every=PT30S", "energy.automation.retry-every=PT31M",
        "energy.automation.retry-every="})
    void should_not_start_with_attempts_or_a_pause_out_of_bounds(final String value) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(AutomationProperties.class)
    static class Properties {
    }
}
