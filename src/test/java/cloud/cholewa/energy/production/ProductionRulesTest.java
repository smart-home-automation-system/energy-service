package cloud.cholewa.energy.production;

import cloud.cholewa.energy.database.SourceAlertEntity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

//the inverter that does not produce in the sun (HAS-333): low for the silence opens one alert, a reminder an hour while
//still low, an info when it produces again; an input not known never holds
class ProductionRulesTest {

    private static final ProductionProperties PROPERTIES = new ProductionProperties(Duration.ofMinutes(15), 20000, 100,
        Duration.ofHours(1));
    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 28, 12, 0);
    private static final ProductionRules.Sky SUNNY = new ProductionRules.Sky(true, 24500.0);
    private static final ProductionRules.Inverter SOLAX_DEAD = new ProductionRules.Inverter("solax", "SOLAX", 40);
    private static final ProductionRules.Inverter SOLAX_WORKING = new ProductionRules.Inverter("solax", "SOLAX", 4200);

    private static SourceAlertEntity open(final LocalDateTime openedAt, final LocalDateTime lastToldAt) {
        return new SourceAlertEntity(5L, "solax", "production", openedAt, lastToldAt, null, null);
    }

    @Test
    void should_start_counting_at_the_first_low_pass() {
        final ProductionRules.Outcome outcome = ProductionRules.decide(SOLAX_DEAD, SUNNY, Optional.empty(), null,
            PROPERTIES, NOON);

        assertThat(outcome.lowSince()).isEqualTo(NOON);
        assertThat(outcome.notice()).isEmpty();
    }

    @Test
    void should_alert_once_low_for_the_silence() {
        final ProductionRules.Outcome outcome = ProductionRules.decide(SOLAX_DEAD, SUNNY, Optional.empty(), NOON,
            PROPERTIES, NOON.plusMinutes(15));

        assertThat(outcome.notice()).contains(ProductionRules.Notice.ALERT);
        assertThat(outcome.text()).isEqualTo("SOLAX produces nothing in the sun: 40 W at 24500 lx since 12:00.");
        assertThat(outcome.row()).contains(new SourceAlertEntity(null, "solax", "production", NOON,
            NOON.plusMinutes(15), null, null));
    }

    @Test
    void should_not_alert_before_the_silence() {
        final ProductionRules.Outcome outcome = ProductionRules.decide(SOLAX_DEAD, SUNNY, Optional.empty(), NOON,
            PROPERTIES, NOON.plusMinutes(14));

        assertThat(outcome.notice()).isEmpty();
        assertThat(outcome.lowSince()).isEqualTo(NOON);
    }

    //a cloud, the evening, a station not known: the count starts anew
    @Test
    void should_count_anew_when_the_sun_does_not_shine() {
        assertThat(ProductionRules.decide(SOLAX_DEAD, new ProductionRules.Sky(true, 19999.0), Optional.empty(), NOON,
            PROPERTIES, NOON.plusMinutes(20)).lowSince()).isNull();
        assertThat(ProductionRules.decide(SOLAX_DEAD, new ProductionRules.Sky(false, 24500.0), Optional.empty(), NOON,
            PROPERTIES, NOON.plusMinutes(20)).lowSince()).isNull();
        assertThat(ProductionRules.decide(SOLAX_DEAD, new ProductionRules.Sky(true, null), Optional.empty(), NOON,
            PROPERTIES, NOON.plusMinutes(20)).notice()).isEmpty();
    }

    @Test
    void should_not_count_an_inverter_that_produces() {
        assertThat(ProductionRules.decide(SOLAX_WORKING, SUNNY, Optional.empty(), NOON, PROPERTIES,
            NOON.plusMinutes(20)).lowSince()).isNull();
    }

    @Test
    void should_remind_every_hour_while_still_low() {
        final ProductionRules.Outcome early = ProductionRules.decide(SOLAX_DEAD, SUNNY,
            Optional.of(open(NOON, NOON.plusMinutes(15))), null, PROPERTIES, NOON.plusMinutes(74));
        final ProductionRules.Outcome due = ProductionRules.decide(SOLAX_DEAD, SUNNY,
            Optional.of(open(NOON, NOON.plusMinutes(15))), null, PROPERTIES, NOON.plusMinutes(75));

        assertThat(early.notice()).isEmpty();
        assertThat(due.notice()).contains(ProductionRules.Notice.REMINDER);
        assertThat(due.text()).isEqualTo("SOLAX still produces nothing in the sun: 40 W at 24500 lx since 12:00.");
        assertThat(due.row()).contains(open(NOON, NOON.plusMinutes(75)));
    }

    //never a reminder in the evening, when the sun is gone
    @Test
    void should_not_remind_when_the_sun_is_gone() {
        assertThat(ProductionRules.decide(SOLAX_DEAD, new ProductionRules.Sky(false, 0.0),
            Optional.of(open(NOON, NOON.plusMinutes(15))), null, PROPERTIES, NOON.plusHours(6)).notice()).isEmpty();
    }

    //closed by a reading at the minimum or above, sun or not
    @Test
    void should_tell_the_production_again_and_close() {
        final ProductionRules.Outcome outcome = ProductionRules.decide(SOLAX_WORKING,
            new ProductionRules.Sky(true, 8000.0), Optional.of(open(NOON, NOON.plusMinutes(15))), null, PROPERTIES,
            NOON.plusMinutes(75));

        assertThat(outcome.notice()).contains(ProductionRules.Notice.INFO);
        assertThat(outcome.text()).isEqualTo("SOLAX produces again: 4200 W - from 12:00 to 13:15 (1 h 15 min).");
        assertThat(outcome.row()).contains(new SourceAlertEntity(5L, "solax", "production", NOON, NOON.plusMinutes(15),
            NOON.plusMinutes(75), null));
    }
}
