package cloud.cholewa.energy.summary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

//application.yaml as the home profile reads it (HAS-339): the summary at 21:30 of the house, and a time that is no
//whole minute - or no time - refuses the start
class SummaryPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_send_the_summary_at_half_past_nine_in_the_evening() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(SummaryProperties.class))
                .isEqualTo(new SummaryProperties(LocalTime.of(21, 30))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"energy.summary.time=21:30:15", "energy.summary.time=25:00", "energy.summary.time="})
    void should_not_start_with_a_time_that_is_no_whole_minute(final String value) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(SummaryProperties.class)
    static class Properties {
    }
}
