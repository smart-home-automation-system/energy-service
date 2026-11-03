package cloud.cholewa.energy.summary;

import cloud.cholewa.energy.api.model.BatteryReply;
import cloud.cholewa.energy.api.model.FlowsReply;
import cloud.cholewa.energy.api.model.HistoryReply;
import cloud.cholewa.energy.api.model.InverterReply;
import cloud.cholewa.energy.database.EnergyDayEntity;
import cloud.cholewa.energy.database.EnergyDayRepository;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import cloud.cholewa.energy.days.SettlementProperties;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.flows.FlowsService;
import cloud.cholewa.energy.history.HistoryService;
import cloud.cholewa.energy.inverter.BatteryService;
import cloud.cholewa.energy.inverter.InverterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//the numbers of the daily summary (HAS-339) from the reads of the service: the day of /flows, each inverter while fresh,
//the SOC while fresh, the hour of the highest production, the silences of the day and the month with today
class SummaryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 21, 30);
    private static final LocalDateTime MIDNIGHT = NOW.toLocalDate().atStartOfDay();

    private final FlowsService flowsService = mock(FlowsService.class);
    private final InverterService inverterService = mock(InverterService.class);
    private final BatteryService batteryService = mock(BatteryService.class);
    private final HistoryService historyService = mock(HistoryService.class);
    private final SourceAlertRepository sourceAlertRepository = mock(SourceAlertRepository.class);
    private final EnergyDayRepository energyDayRepository = mock(EnergyDayRepository.class);
    private final SummaryService service = new SummaryService(flowsService, inverterService, batteryService,
        historyService, sourceAlertRepository, energyDayRepository, new SettlementProperties(new BigDecimal("0.7"), 9));

    private static BigDecimal bd(final String value) {
        return new BigDecimal(value);
    }

    private static InverterReply inverter(final String id, final double dailyYield, final boolean stale) {
        return new InverterReply(id, 134L, id, "garage", null, null, null, null, null, 0.0, null, null, null, null,
            null, dailyYield, null, NOW, stale, "local");
    }

    private static BatteryReply battery(final double soc, final boolean stale) {
        return new BatteryReply(soc, null, null, null, null, null, null, null, null, 8.0, 7.6, NOW, stale, "local");
    }

    private static HistoryReply.Point point(final int hour, final int minute, final String watts) {
        return new HistoryReply.Point(MIDNIGHT.withHour(hour).withMinute(minute), bd(watts), null, null);
    }

    private static SourceAlertEntity silent(final String source, final LocalDateTime from, final LocalDateTime to) {
        return new SourceAlertEntity(1L, source, "silent", from, from, to, null);
    }

    private static EnergyDayEntity closed(final int day, final Double imported, final Double exported) {
        return new EnergyDayEntity(LocalDate.of(2026, 11, day), null, null, null, imported, exported, null, null, null,
            null, null, null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        when(flowsService.flows()).thenReturn(Mono.just(new FlowsReply(null, null, null, null, null, null,
            new FlowsReply.Today(bd("18.4"), bd("6.1"), bd("3.3"), bd("8.0"), bd("7.6"), bd("21.7"), bd("0.821"),
                bd("0.281")), Map.of())));
        when(inverterService.inverter("solax")).thenReturn(Mono.just(inverter("solax", 11.2, false)));
        when(inverterService.inverter("sofar")).thenReturn(Mono.just(inverter("sofar", 7.2, false)));
        when(batteryService.battery()).thenReturn(Mono.just(battery(64.0, false)));
        when(historyService.history("pv-power", MIDNIGHT, NOW)).thenReturn(Mono.just(new HistoryReply("pv-power",
            MIDNIGHT, NOW, 300, List.of(point(11, 0, "3000"), point(12, 0, "4800"), point(12, 5, "5000")))));
        when(sourceAlertRepository.findOverlapping("silent", MIDNIGHT, NOW)).thenReturn(Flux.empty());
        when(energyDayRepository.findByDayBetweenOrderByDay(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)))
            .thenReturn(Flux.just(closed(1, 20.0, 7.0), closed(2, 14.9, null)));
    }

    @Test
    void should_gather_the_day_the_peak_and_the_month_with_today() {
        service.summary(NOW).as(StepVerifier::create)
            .assertNext(summary -> {
                assertThat(summary.solax()).isEqualByComparingTo("11.2");
                assertThat(summary.sofar()).isEqualByComparingTo("7.2");
                assertThat(summary.production()).isEqualByComparingTo("18.4");
                assertThat(summary.soc()).isEqualByComparingTo("64");
                assertThat(summary.peak()).isEqualTo(new DaySummary.Peak(LocalTime.NOON, bd("4900.00")));
                assertThat(summary.month()).isEqualTo(new DaySummary.Month(YearMonth.of(2026, 11), bd("41.0"),
                    bd("10.3"), bd("0.7"), true));
                assertThat(summary.solaxSilent()).isFalse();
                assertThat(summary.meterSilent()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void should_take_a_stale_inverter_and_battery_and_an_unread_history_for_not_known() {
        when(inverterService.inverter("sofar")).thenReturn(Mono.just(inverter("sofar", 7.2, true)));
        when(inverterService.inverter("solax")).thenReturn(Mono.error(EnergyException.registryUnavailable()));
        when(batteryService.battery()).thenReturn(Mono.just(battery(64.0, true)));
        when(historyService.history(any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("down")));

        service.summary(NOW).as(StepVerifier::create)
            .assertNext(summary -> {
                assertThat(summary.solax()).isNull();
                assertThat(summary.sofar()).isNull();
                assertThat(summary.soc()).isNull();
                assertThat(summary.peak()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void should_leave_today_out_of_the_month_while_the_meter_was_silent_for_most_of_the_day() {
        when(sourceAlertRepository.findOverlapping("silent", MIDNIGHT, NOW)).thenReturn(Flux.just(
            silent("meter", MIDNIGHT.minusHours(2), NOW.withHour(11)), silent("solax", NOW.withHour(20), null)));

        service.summary(NOW).as(StepVerifier::create)
            .assertNext(summary -> {
                assertThat(summary.meterSilent()).isTrue();
                assertThat(summary.solaxSilent()).isFalse();
                assertThat(summary.month()).isEqualTo(new DaySummary.Month(YearMonth.of(2026, 11), bd("34.9"),
                    bd("7.0"), bd("0.7"), false));
            })
            .verifyComplete();
    }

    @Test
    void should_fail_when_the_database_fails() {
        when(sourceAlertRepository.findOverlapping("silent", MIDNIGHT, NOW))
            .thenReturn(Flux.error(new IllegalStateException("database down")));

        service.summary(NOW).as(StepVerifier::create).verifyError(IllegalStateException.class);
    }

    //on the first of a month only today counts - the closed days are not asked for
    @Test
    void should_count_only_today_on_the_first_of_a_month() {
        final LocalDateTime first = LocalDateTime.of(2026, 12, 1, 21, 30);
        final LocalDateTime start = first.toLocalDate().atStartOfDay();
        when(historyService.history("pv-power", start, first)).thenReturn(Mono.just(new HistoryReply("pv-power",
            start, first, 300, List.of())));
        when(sourceAlertRepository.findOverlapping("silent", start, first)).thenReturn(Flux.empty());

        service.summary(first).as(StepVerifier::create)
            .assertNext(summary -> assertThat(summary.month()).isEqualTo(new DaySummary.Month(YearMonth.of(2026, 12),
                bd("6.1"), bd("3.3"), bd("0.7"), true)))
            .verifyComplete();
    }

    @Test
    void should_find_the_hour_of_the_highest_production_and_none_at_night() {
        assertThat(SummaryService.peakOf(List.of(point(9, 0, "100"), point(13, 0, "2000"), point(13, 30, "1000"),
            point(14, 0, "1400")))).contains(new DaySummary.Peak(LocalTime.of(13, 0), bd("1500.00")));
        assertThat(SummaryService.peakOf(List.of(point(1, 0, "0"), point(2, 0, "0")))).isEmpty();
        assertThat(SummaryService.peakOf(List.of())).isEmpty();
    }

    //more than half of the day so far, the episodes of a source added up, cut to the day
    @Test
    void should_take_a_source_for_silent_above_half_of_the_day_so_far() {
        final LocalDateTime noon = MIDNIGHT.withHour(12);

        assertThat(SummaryService.silentOf(List.of(silent("meter", MIDNIGHT.minusHours(5), MIDNIGHT.withHour(4)),
            silent("meter", MIDNIGHT.withHour(9), null)), MIDNIGHT, noon)).containsExactly("meter");
        assertThat(SummaryService.silentOf(List.of(silent("solax", MIDNIGHT.withHour(6), noon)), MIDNIGHT, noon))
            .isEmpty();
    }
}
