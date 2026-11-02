package cloud.cholewa.energy.history;

import cloud.cholewa.energy.days.SettlementProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

//application.yaml as the home profile reads it (HAS-331): the retention of the readings - its unit and its bounds, a
//value that decides what is deleted - and the settlement of the house
class RetentionPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_keep_the_readings_for_two_years() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(RetentionProperties.class).retention())
                .isEqualTo(Duration.ofDays(730)));
    }

    //without the unit a bare number would bind as milliseconds and the first night would delete everything
    @Test
    void should_read_a_bare_number_as_days() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "energy.history.retention=400")
            .run(context -> assertThat(context.getBean(RetentionProperties.class).retention())
                .isEqualTo(Duration.ofDays(400)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"energy.history.retention=30", "energy.history.retention=30d",
        "energy.history.retention=PT12H", "energy.history.retention=3651"})
    void should_not_start_with_a_retention_out_of_bounds(final String retention) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", retention)
            .run(context -> assertThat(context).hasFailed());
    }

    //owner: net-metering at 0.7, the billing year from September (the installation started in September 2020)
    @Test
    void should_settle_at_the_ratio_of_the_agreement_from_september() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> {
                final SettlementProperties settlement = context.getBean(SettlementProperties.class);
                assertThat(settlement.ratio()).isEqualByComparingTo(new BigDecimal("0.7"));
                assertThat(settlement.periodStart()).isEqualTo(9);
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"energy.settlement.ratio=0", "energy.settlement.ratio=1.2", "energy.settlement.period-start=0",
        "energy.settlement.period-start=13"})
    void should_not_start_with_a_settlement_out_of_bounds(final String settlement) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", settlement)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties({RetentionProperties.class, SettlementProperties.class})
    static class Properties {
    }
}
