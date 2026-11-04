package cloud.cholewa.energy.forecast;

import cloud.cholewa.energy.api.model.ForecastReply;
import cloud.cholewa.energy.database.EnergyDayEntity;
import cloud.cholewa.energy.database.EnergyDayRepository;
import cloud.cholewa.energy.database.PvForecastCalibrationEntity;
import cloud.cholewa.energy.database.PvForecastCalibrationRepository;
import cloud.cholewa.energy.database.PvForecastDayEntity;
import cloud.cholewa.energy.database.PvForecastDayRepository;
import cloud.cholewa.energy.database.PvForecastEntity;
import cloud.cholewa.energy.database.PvForecastRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//GET /forecast from what is stored (HAS-372): the kWh of a day at the stored ratio (the initial one without a row), its
//coverage and category against the house load of the last week, the cloud cover of the daytime and the sunshine hours,
//the next 48 hours, the last two days compared, the outlook, staleness, and what is left out when it is not known
class ForecastServiceTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private static final LocalDateTime NOW = TODAY.atTime(10, 30);
    //the SOLAX 10 kWp, the SOFAR 5 kWp
    private static final ForecastProperties FORECAST = new ForecastProperties(URI.create("http://localhost:1"),
        Duration.ofHours(1), new ForecastProperties.Plane(35.0, 0.0, 10.0), new ForecastProperties.Plane(37.0, 0.0, 5.0));
    private static final OutlookProperties OUTLOOK = new OutlookProperties(0.8, 28, 5, Duration.ofHours(3),
        new ForecastRules.Bands(0.10, 0.40, 0.80));

    private final PvForecastRepository pvForecastRepository = mock(PvForecastRepository.class);
    private final PvForecastDayRepository pvForecastDayRepository = mock(PvForecastDayRepository.class);
    private final PvForecastCalibrationRepository calibrationRepository = mock(PvForecastCalibrationRepository.class);
    private final EnergyDayRepository energyDayRepository = mock(EnergyDayRepository.class);
    private final ForecastState state = new ForecastState();
    private final List<PvForecastEntity> hours = new ArrayList<>();
    private ForecastService sut;

    @BeforeEach
    void setUp() {
        sut = new ForecastService(pvForecastRepository, pvForecastDayRepository, calibrationRepository,
            energyDayRepository, state, FORECAST, OUTLOOK, Clock.fixed(NOW.atZone(HOUSE).toInstant(), HOUSE));
        when(calibrationRepository.findAll()).thenReturn(Flux.just(
            new PvForecastCalibrationEntity("solax", 0.5, 12, NOW.minusHours(9))));
        when(pvForecastRepository.findBetween(TODAY.atStartOfDay(), TODAY.plusDays(7).atStartOfDay()))
            .thenAnswer(invocation -> Flux.fromIterable(hours));
        when(energyDayRepository.findTop7ByDayLessThanOrderByDayDesc(TODAY)).thenReturn(Flux.just(
            closedDay(TODAY.minusDays(1), 10.0), closedDay(TODAY.minusDays(2), null), closedDay(TODAY.minusDays(3), 14.0)));
        when(pvForecastDayRepository.findBetween(TODAY.minusDays(2), TODAY.minusDays(1))).thenReturn(Flux.just(
            new PvForecastDayEntity(TODAY.minusDays(2), "solax", 20.0, 9.0, NOW),
            new PvForecastDayEntity(TODAY.minusDays(1), "sofar", 4.0, 2.5, NOW),
            new PvForecastDayEntity(TODAY.minusDays(1), "solax", 8.0, 3.9, NOW)));
        state.fetched("solax", NOW.minusMinutes(30));
        state.fetched("sofar", NOW.minusMinutes(20));
    }

    private static EnergyDayEntity closedDay(final LocalDate day, final Double houseLoad) {
        return new EnergyDayEntity(day, null, null, null, null, null, null, null, houseLoad, null, null, null, null, null,
            day.plusDays(1).atTime(0, 20));
    }

    //an hour of both planes: the irradiance of each, the sky the same
    private void hour(final LocalDateTime hour, final double solax, final double sofar, final double horizontal,
                      final double cloud) {
        hours.add(new PvForecastEntity(hour, "sofar", sofar, horizontal, cloud, 5.0, 0.0, NOW.minusHours(1)));
        hours.add(new PvForecastEntity(hour, "solax", solax, horizontal, cloud, 5.0, 0.0, NOW.minusHours(1)));
    }

    private ForecastReply reply() {
        final ForecastReply[] reply = new ForecastReply[1];
        sut.forecast().as(StepVerifier::create).consumeNextWith(answer -> reply[0] = answer).verifyComplete();
        return reply[0];
    }

    @Test
    void should_turn_the_forecast_of_a_day_into_its_kwh_and_category() {
        //today: a night hour, 08:00 and 12:00 with sun, 15:00 cloudy
        hour(TODAY.atTime(3, 0), 0, 0, 0, 100);
        hour(TODAY.atTime(8, 0), 200, 100, 150, 20);
        hour(TODAY.atTime(12, 0), 400, 300, 300, 40);
        hour(TODAY.atTime(15, 0), 100, 50, 60, 90);

        final ForecastReply reply = reply();

        //the SOLAX at 0.5: 10 x 700 / 1000 x 0.5 = 3.5; the SOFAR at the initial 0.8: 5 x 450 / 1000 x 0.8 = 1.8; the
        //reference is the mean of 10 and 14 - 12 kWh, 5.3 / 12 = 0.44: fair
        assertThat(reply.days()).hasSize(7);
        assertThat(reply.days().getFirst()).isEqualTo(new ForecastReply.Day(TODAY, 3.5, 1.8, 5.3, 0.44,
            ForecastCategory.FAIR, 50.0, 2));
        assertThat(reply.days().get(1)).isEqualTo(new ForecastReply.Day(TODAY.plusDays(1), null, null, null, null, null,
            null, null));
        assertThat(reply.houseLoadKwh()).isEqualTo(12.0);
        assertThat(reply.houseLoadDays()).isEqualTo(2);
        assertThat(reply.performanceRatio()).isEqualTo(new ForecastReply.Ratios(new ForecastReply.Ratio(0.5, 12),
            new ForecastReply.Ratio(0.8, 0)));
        assertThat(reply.outlook()).isEqualTo(new ForecastReply.Outlook(TODAY, 0));
    }

    @Test
    void should_find_the_day_the_sun_comes_back() {
        for (int day = 0; day < 7; day++) {
            final double sun = day == 4 ? 600 : 20;
            hour(TODAY.plusDays(day).atTime(12, 0), sun, sun, sun, 50);
        }

        final ForecastReply reply = reply();

        //20 W/m2 at noon: 0.1 + 0.08 = 0.18 kWh, 0.015 of 12 - none; 600: 3 + 2.4 = 5.4, 0.45 - fair
        assertThat(reply.days()).extracting(ForecastReply.Day::category).containsExactly(ForecastCategory.NONE,
            ForecastCategory.NONE, ForecastCategory.NONE, ForecastCategory.NONE, ForecastCategory.FAIR,
            ForecastCategory.NONE, ForecastCategory.NONE);
        assertThat(reply.outlook()).isEqualTo(new ForecastReply.Outlook(TODAY.plusDays(4), 4));
    }

    @Test
    void should_give_the_next_forty_eight_hours_from_the_hour_now() {
        hour(TODAY.atTime(9, 0), 100, 100, 100, 10);
        hour(TODAY.atTime(10, 0), 300, 200, 250, 30);
        hour(TODAY.plusDays(2).atTime(9, 0), 100, 100, 100, 10);
        hour(TODAY.plusDays(2).atTime(10, 0), 100, 100, 100, 10);

        final ForecastReply reply = reply();

        //10:00 today to 09:00 the day after tomorrow, both included
        assertThat(reply.hourly()).containsExactly(
            new ForecastReply.Hour(TODAY.atTime(10, 0), 1.5, 0.8, 30.0),
            new ForecastReply.Hour(TODAY.plusDays(2).atTime(9, 0), 0.5, 0.4, 10.0));
    }

    @Test
    void should_compare_the_last_two_days_at_the_ratio_in_use() {
        final ForecastReply reply = reply();

        assertThat(reply.past()).containsExactly(
            new ForecastReply.Past(TODAY.minusDays(2), new ForecastReply.Compared(10.0, 9.0), null),
            new ForecastReply.Past(TODAY.minusDays(1), new ForecastReply.Compared(4.0, 3.9),
                new ForecastReply.Compared(3.2, 2.5)));
    }

    @Test
    void should_take_the_older_fetch_and_judge_it_fresh() {
        final ForecastReply reply = reply();

        assertThat(reply.fetchedAt()).isEqualTo(NOW.minusMinutes(30));
        assertThat(reply.stale()).isFalse();
    }

    @Test
    void should_be_stale_after_three_hours() {
        state.fetched("solax", NOW.minusHours(3).minusMinutes(1));

        assertThat(reply().stale()).isTrue();
    }

    @Test
    void should_be_stale_while_a_plane_was_never_fetched() {
        final ForecastState fresh = new ForecastState();
        fresh.fetched("solax", NOW.minusMinutes(5));
        sut = new ForecastService(pvForecastRepository, pvForecastDayRepository, calibrationRepository,
            energyDayRepository, fresh, FORECAST, OUTLOOK, Clock.fixed(NOW.atZone(HOUSE).toInstant(), HOUSE));

        final ForecastReply reply = reply();

        assertThat(reply.fetchedAt()).isNull();
        assertThat(reply.stale()).isTrue();
    }

    @Test
    void should_leave_out_the_categories_without_a_house_load() {
        when(energyDayRepository.findTop7ByDayLessThanOrderByDayDesc(TODAY)).thenReturn(Flux.empty());
        hour(TODAY.atTime(12, 0), 400, 300, 300, 40);

        final ForecastReply reply = reply();

        assertThat(reply.houseLoadKwh()).isNull();
        assertThat(reply.houseLoadDays()).isNull();
        assertThat(reply.days().getFirst().totalKwh()).isEqualTo(3.2);
        assertThat(reply.days()).allSatisfy(day -> {
            assertThat(day.coverage()).isNull();
            assertThat(day.category()).isNull();
        });
        assertThat(reply.outlook()).isNull();
    }
}
