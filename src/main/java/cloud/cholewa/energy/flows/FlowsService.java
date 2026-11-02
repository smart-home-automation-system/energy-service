package cloud.cholewa.energy.flows;

import cloud.cholewa.energy.api.model.BatteryReply;
import cloud.cholewa.energy.api.model.FlowsReply;
import cloud.cholewa.energy.api.model.GridReply;
import cloud.cholewa.energy.api.model.InverterReply;
import cloud.cholewa.energy.days.DayTotals;
import cloud.cholewa.energy.history.StoredValue;
import cloud.cholewa.energy.history.StoredValues;
import cloud.cholewa.energy.inverter.BatteryService;
import cloud.cholewa.energy.inverter.InverterService;
import cloud.cholewa.energy.meter.GridService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//GET /home/energy/flows (HAS-331): the balance of the house now, from what the other reads answer - the grid meter
//(/grid), both inverters (/inverters, the SolaX cloud standing for the dongle as there) and the battery (/battery); none
//of them is asked for a request. An input is known when it has a value and is not stale; what depends on an input not
//known is left out. The counters of the grid meter at midnight come from grid_reading once a day (held in memory for the
//day); a database that cannot be read leaves imported and exported of today out
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowsService {

    private static final String SOLAX = "solax";
    private static final String SOFAR = "sofar";
    static final Duration HOLD_AFTER = Duration.ofMinutes(5);

    private final GridService gridService;
    private final InverterService inverterService;
    private final BatteryService batteryService;
    private final StoredValues storedValues;
    private final Clock clock;
    private final AtomicReference<Midnight> midnight = new AtomicReference<>();

    //the counters of the grid meter at the start of a day, kWh
    private record Midnight(LocalDate day, BigDecimal imported, BigDecimal exported) {
    }

    record Inputs(GridReply grid, Optional<InverterReply> solax, Optional<InverterReply> sofar,
                          BatteryReply battery, Optional<Midnight> midnight) {
    }

    public Mono<FlowsReply> flows() {
        return Mono.defer(() -> Mono.zip(
                gridService.grid(),
                inverter(SOLAX),
                inverter(SOFAR),
                batteryService.battery(),
                midnight(LocalDateTime.now(clock))))
            .map(read -> reply(new Inputs(read.getT1(), read.getT2(), read.getT3(), read.getT4(), read.getT5())));
    }

    //an inverter not registered, or a registry not known yet, is an input not known
    private Mono<Optional<InverterReply>> inverter(final String id) {
        return inverterService.inverter(id)
            .map(Optional::of)
            .onErrorResume(failure -> Mono.just(Optional.empty()));
    }

    //held for the day only once HOLD_AFTER past midnight: the last row of the day before is written a minute after its
    //reading, and a request right after midnight would hold the counters a row early (the /code-review of HAS-331)
    private Mono<Optional<Midnight>> midnight(final LocalDateTime now) {
        final LocalDate today = now.toLocalDate();
        return Mono.justOrEmpty(midnight.get())
            .filter(held -> held.day().equals(today))
            //one query after the other - the pool has two connections
            .switchIfEmpty(Mono.defer(() -> storedValues.lastBefore(StoredValue.GRID_IMPORT, today.atStartOfDay())
                .zipWhen(imported -> storedValues.lastBefore(StoredValue.GRID_EXPORT, today.atStartOfDay()))
                .map(read -> new Midnight(today, read.getT1(), read.getT2()))
                .doOnNext(read -> Optional.of(read)
                    .filter(settled -> !now.isBefore(today.atStartOfDay().plus(HOLD_AFTER)))
                    .ifPresent(midnight::set))))
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The counters of the grid meter at midnight could not be read: {}", failure.toString()))
                .thenReturn(Optional.empty()));
    }

    static FlowsReply reply(final Inputs inputs) {
        final Double solaxPv = inputs.solax().filter(reply -> !reply.stale()).map(InverterReply::pvPower).orElse(null);
        final Double sofarPv = inputs.sofar().filter(reply -> !reply.stale()).map(InverterReply::pvPower).orElse(null);
        final Double gridPower = inputs.grid().stale() ? null : inputs.grid().totalActivePower();
        final Double batteryPower = inputs.battery().stale() ? null : inputs.battery().power();
        final Double production = solaxPv == null || sofarPv == null ? null : solaxPv + sofarPv;
        final Double houseLoad = production == null || gridPower == null || batteryPower == null ? null
            : production + gridPower - batteryPower;
        final Map<String, FlowsReply.Input> known = new LinkedHashMap<>();
        known.put("grid", new FlowsReply.Input(inputs.grid().measuredAt(), inputs.grid().stale()));
        known.put(SOLAX, input(inputs.solax()));
        known.put(SOFAR, input(inputs.sofar()));
        known.put("battery", new FlowsReply.Input(inputs.battery().measuredAt(), inputs.battery().stale()));
        return new FlowsReply(production, solaxPv, sofarPv, gridPower, batteryPower, houseLoad, today(inputs), known);
    }

    private static FlowsReply.Today today(final Inputs inputs) {
        final BigDecimal production = DayTotals.sum(dailyYield(inputs.solax()), dailyYield(inputs.sofar()));
        final boolean gridKnown = !inputs.grid().stale();
        final BigDecimal imported = inputs.midnight()
            .filter(start -> gridKnown)
            .flatMap(start -> delta(inputs.grid().importEnergy(), start.imported()))
            .orElse(null);
        final BigDecimal exported = inputs.midnight()
            .filter(start -> gridKnown)
            .flatMap(start -> delta(inputs.grid().exportEnergy(), start.exported()))
            .orElse(null);
        final BatteryReply battery = inputs.battery();
        final BigDecimal charged = battery.stale() ? null : decimal(battery.chargedToday());
        final BigDecimal discharged = battery.stale() ? null : decimal(battery.dischargedToday());
        final DayTotals totals = new DayTotals(production, imported, exported, charged, discharged);
        return new FlowsReply.Today(production, imported, exported, charged, discharged, totals.houseLoad(),
            totals.selfConsumption(), totals.gridShare());
    }

    //the production of an inverter today: its daily counter, while it is not stale
    private static BigDecimal dailyYield(final Optional<InverterReply> inverter) {
        return inverter.filter(reply -> !reply.stale()).map(InverterReply::dailyYield).map(BigDecimal::valueOf)
            .orElse(null);
    }

    private static Optional<BigDecimal> delta(final Double now, final BigDecimal atMidnight) {
        return Optional.ofNullable(now)
            .map(value -> BigDecimal.valueOf(value).subtract(atMidnight).setScale(2, RoundingMode.HALF_UP));
    }

    private static BigDecimal decimal(final Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static FlowsReply.Input input(final Optional<InverterReply> inverter) {
        return inverter.map(reply -> new FlowsReply.Input(reply.measuredAt(), reply.stale()))
            .orElseGet(() -> new FlowsReply.Input(null, true));
    }
}
