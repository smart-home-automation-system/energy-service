package cloud.cholewa.energy.forecast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

//application.yaml as the home profile reads it (HAS-371): Open-Meteo every hour, the planes of the house from the six
//variables of the manifest, a bare interval in minutes, and the bounds that refuse the start
class ForecastPropertiesTest {

    private static final String[] PLANES = {"pv-solax-tilt=35", "pv-solax-azimuth=0", "pv-solax-peak-kwp=8.24",
        "pv-sofar-tilt=37", "pv-sofar-azimuth=0", "pv-sofar-peak-kwp=5"};

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class)
        .withPropertyValues("spring.profiles.active=home");

    @Test
    void should_fetch_both_planes_of_the_house_every_hour() {
        contextRunner
            .withPropertyValues(PLANES)
            .run(context -> {
                final ForecastProperties properties = context.getBean(ForecastProperties.class);
                assertThat(properties).isEqualTo(new ForecastProperties(URI.create("https://api.open-meteo.com"),
                    Duration.ofHours(1), new ForecastProperties.Plane(35.0, 0.0, 8.24),
                    new ForecastProperties.Plane(37.0, 0.0, 5.0)));
                assertThat(properties.planes()).containsOnlyKeys("solax", "sofar");
                assertThat(properties.planes().keySet()).containsExactly("solax", "sofar");
            });
    }

    @Test
    void should_read_a_bare_interval_as_minutes() {
        contextRunner
            .withPropertyValues(PLANES)
            .withPropertyValues("energy.forecast.interval=90")
            .run(context -> assertThat(context.getBean(ForecastProperties.class).interval())
                .isEqualTo(Duration.ofMinutes(90)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"energy.forecast.interval=14", "energy.forecast.interval=PT6H1M",
        "energy.forecast.solax.tilt=91", "energy.forecast.solax.tilt=-1", "energy.forecast.sofar.azimuth=181",
        "energy.forecast.sofar.azimuth=-181", "energy.forecast.solax.peak-kwp=0", "energy.forecast.sofar.peak-kwp=101"})
    void should_not_start_with_a_value_out_of_its_range(final String value) {
        contextRunner
            .withPropertyValues(PLANES)
            .withPropertyValues(value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void should_not_start_without_a_plane_of_the_manifest() {
        contextRunner
            .withPropertyValues("pv-solax-tilt=35", "pv-solax-azimuth=0", "pv-solax-peak-kwp=8.24",
                "pv-sofar-tilt=37", "pv-sofar-azimuth=0")
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(ForecastProperties.class)
    static class Properties {
    }
}
