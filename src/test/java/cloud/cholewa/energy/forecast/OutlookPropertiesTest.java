package cloud.cholewa.energy.forecast;

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

//application.yaml as the home profile reads it (HAS-372): the initial ratio, the window of the calibration, the
//staleness and the bands of the coverage, a bare stale-after in minutes, and the values that refuse the start
class OutlookPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class)
        .withPropertyValues("spring.profiles.active=home");

    @Test
    void should_read_the_outlook_of_the_house() {
        contextRunner.run(context -> assertThat(context.getBean(OutlookProperties.class)).isEqualTo(
            new OutlookProperties(0.8, 28, 5, Duration.ofHours(3), new ForecastRules.Bands(0.10, 0.40, 0.80))));
    }

    @Test
    void should_read_a_bare_stale_after_as_minutes() {
        contextRunner
            .withPropertyValues("energy.forecast.stale-after=90")
            .run(context -> assertThat(context.getBean(OutlookProperties.class).staleAfter())
                .isEqualTo(Duration.ofMinutes(90)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"energy.forecast.initial-performance-ratio=0.29",
        "energy.forecast.initial-performance-ratio=1.01", "energy.forecast.calibration-days=6",
        "energy.forecast.calibration-days=91", "energy.forecast.min-calibration-days=0",
        "energy.forecast.min-calibration-days=29", "energy.forecast.stale-after=59",
        "energy.forecast.stale-after=PT24H1M", "energy.forecast.bands.none-below=0.40",
        "energy.forecast.bands.fair-below=0.39", "energy.forecast.bands.poor-below=-0.1",
        "energy.forecast.bands.fair-below=10.1"})
    void should_not_start_with_a_value_out_of_its_range(final String value) {
        contextRunner
            .withPropertyValues(value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(OutlookProperties.class)
    static class Properties {
    }
}
