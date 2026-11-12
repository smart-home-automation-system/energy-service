package cloud.cholewa.energy.elicznik;

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

//energy.elicznik (HAS-340): the defaults of application.yaml, the units, the bounds, and an account never printed
class ElicznikPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class)
        .withPropertyValues("spring.profiles.active=home", "elicznik-login=owner@example.com", "elicznik-password=s3cr3t",
            "elicznik-meter-id=1_2_3");

    @Test
    void should_bind_the_defaults() {
        contextRunner.run(context -> {
            final ElicznikProperties properties = context.getBean(ElicznikProperties.class);
            assertThat(properties.retryDays()).isEqualTo(7);
            assertThat(properties.alertAfter()).isEqualTo(Duration.ofDays(3));
            assertThat(properties.warnAbove()).isEqualByComparingTo("0.05");
            assertThat(properties.fetchEvery()).isEqualTo(Duration.ofHours(1));
            assertThat(properties.serviceUrl()).hasToString("https://elicznik.tauron-dystrybucja.pl");
        });
    }

    //HAS-422: 10:00, not 06:00 - at 06:00 the portal had not served the day before
    @Test
    void should_fetch_at_ten_in_the_morning() {
        contextRunner.run(context -> assertThat(context.getEnvironment().getProperty("energy.elicznik.fetch-cron"))
            .isEqualTo("0 0 10 * * *"));
    }

    //a bare number of alert-after is days - as milliseconds it would alert at the first failure
    @Test
    void should_read_a_bare_alert_after_as_days() {
        contextRunner.withPropertyValues("energy.elicznik.alert-after=2")
            .run(context -> assertThat(context.getBean(ElicznikProperties.class).alertAfter()).isEqualTo(Duration.ofDays(2)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "energy.elicznik.meter-id=2044663",
        "energy.elicznik.retry-days=0",
        "energy.elicznik.retry-days=32",
        "energy.elicznik.alert-after=15",
        "energy.elicznik.warn-above=0.6",
        "energy.elicznik.fetch-every=PT5M",
        "energy.elicznik.password="
    })
    void should_not_start_with_a_value_out_of_its_range(final String value) {
        contextRunner.withPropertyValues(value).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void should_never_print_the_account() {
        contextRunner.run(context -> assertThat(context.getBean(ElicznikProperties.class).toString())
            .doesNotContain("owner@example.com").doesNotContain("s3cr3t").doesNotContain("1_2_3"));
    }

    @Configuration
    @EnableConfigurationProperties(ElicznikProperties.class)
    static class Properties {
    }
}
