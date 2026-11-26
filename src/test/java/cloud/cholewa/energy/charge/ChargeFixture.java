package cloud.cholewa.energy.charge;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.energy.api.model.BatteryReply;
import cloud.cholewa.energy.database.BatteryChargeRunEntity;
import cloud.cholewa.energy.database.BatteryChargeRunRepository;
import cloud.cholewa.energy.database.EnergyReserveEntity;
import cloud.cholewa.energy.database.EnergyReserveRepository;
import cloud.cholewa.energy.api.model.GridReply;
import cloud.cholewa.energy.automation.AutomationPlan;
import cloud.cholewa.energy.database.PlannedOutageRepository;
import cloud.cholewa.energy.inverter.BatteryService;
import cloud.cholewa.energy.tauron.OutageLane;
import cloud.cholewa.energy.meter.GridService;
import cloud.cholewa.energy.registry.InverterRegistry;
import cloud.cholewa.energy.solax.SolaxProperties;
import cloud.cholewa.energy.solax.SolaxSamples;
import cloud.cholewa.energy.solax.SolaxSettingsProperties;
import cloud.cholewa.energy.solax.SolaxSettingsTracker;
import cloud.cholewa.energy.solaxcloud.SolaxCloudClient;
import cloud.cholewa.home.model.InverterBrand;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//The charge with a table in memory, a stubbed SolaX cloud, the SOLAX of the registry and a clock moved on (HAS-336)
final class ChargeFixture {

    final SolaxSamples.MovableClock clock = new SolaxSamples.MovableClock();
    final SolaxCloudClient client = mock(SolaxCloudClient.class);
    final InverterRegistry registry = mock(InverterRegistry.class);
    final BatteryService batteryService = mock(BatteryService.class);
    final GridService gridService = mock(GridService.class);
    final PlannedOutageRepository outages = mock(PlannedOutageRepository.class);
    final AutomationPlan plan = new AutomationPlan();
    final NotificationPublisher publisher = mock(NotificationPublisher.class);
    final BatteryChargeRunRepository repository = mock(BatteryChargeRunRepository.class);
    final Map<Long, BatteryChargeRunEntity> table = new TreeMap<>();
    final ChargeWatchFlag flag = new ChargeWatchFlag();
    final EnergyReserveRepository reserves = mock(EnergyReserveRepository.class);
    final AtomicReference<EnergyReserveEntity> reserveRow = new AtomicReference<>(ChargeSamples.reserveNeverWritten());
    final SolaxSettingsTracker settings = new SolaxSettingsTracker(new SolaxSettingsProperties(
        Duration.ofMinutes(5), Duration.ofSeconds(20), Duration.ofMinutes(15)), clock);
    final ReserveService reserve;
    final InverterCommands commands;
    final ChargeService service;
    final ChargeWatch watch;
    private final AtomicLong ids = new AtomicLong();

    ChargeFixture() {
        moveTo(ChargeSamples.NOW);
        final SolaxProperties solax = new SolaxProperties(Duration.ofSeconds(15), Duration.ofMinutes(1), 26.5);
        commands = new InverterCommands(client, registry, ChargeSamples.PROPERTIES, SolaxSamples.JSON, settings,
            clock);
        reserve = new ReserveService(reserves, repository, commands, publisher, ChargeSamples.PROPERTIES, settings,
            batteryService, ChargeSamples.SOC, clock);
        service = new ChargeService(repository, commands, batteryService, gridService, publisher,
            ChargeSamples.PROPERTIES, solax, flag, outages, new OutageLane(), plan, reserve, clock);
        watch = new ChargeWatch(repository, commands, service, ChargeSamples.PROPERTIES, flag, clock);
        when(registry.current()).thenReturn(Optional.of(List.of(SolaxSamples.solax("192.0.2.1"))));
        when(registry.of(InverterBrand.SOLAX)).thenReturn(Optional.of(SolaxSamples.solax("192.0.2.1")));
        battery(45.0, false);
        grid(300.0);
        when(client.setSelfUse(any())).thenReturn(Mono.just("r-1"));
        when(client.setBackUp(any())).thenReturn(Mono.just("r-1"));
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(reserves.findById(EnergyReserveEntity.ROW_ID)).thenAnswer(call -> Mono.justOrEmpty(reserveRow.get()));
        when(reserves.save(any())).thenAnswer(call -> {
            reserveRow.set(call.getArgument(0));
            return Mono.just(reserveRow.get());
        });
        when(outages.setSkipAutomation(any(), anyBoolean())).thenReturn(Mono.just(1));
        when(repository.save(any())).thenAnswer(call -> {
            final BatteryChargeRunEntity row = call.getArgument(0);
            final BatteryChargeRunEntity saved = row.id() == null ? row.withId(ids.incrementAndGet()) : row;
            table.put(saved.id(), saved);
            return Mono.just(saved);
        });
        when(repository.findFirstByOrderByRequestedAtDesc()).thenAnswer(call -> Mono.justOrEmpty(table.values().stream()
            .max(Comparator.comparing(BatteryChargeRunEntity::requestedAt))));
        when(repository.adopt(any(), any())).thenAnswer(call -> {
            final List<BatteryChargeRunEntity> twins = table.values().stream()
                .filter(run -> call.getArgument(1).equals(run.plannedOutageId()))
                .toList();
            twins.forEach(run -> table.put(run.id(), run.withPlannedOutageId(call.getArgument(0))));
            return Mono.just(twins.size());
        });
        when(repository.findOfOutage(any(), any())).thenAnswer(call -> {
            final Long twin = Optional.ofNullable(call.<Long>getArgument(1)).map(table::get)
                .map(BatteryChargeRunEntity::plannedOutageId).orElse(null);
            return Flux.fromIterable(table.values().stream()
                .filter(run -> call.getArgument(0).equals(run.plannedOutageId())
                    || run.id().equals(call.getArgument(1))
                    || twin != null && twin.equals(run.plannedOutageId()))
                .sorted(Comparator.comparing(BatteryChargeRunEntity::requestedAt))
                .toList());
        });
        when(repository.findByRequestedAtGreaterThanEqualAndRequestedAtLessThanOrderByRequestedAt(any(), any()))
            .thenAnswer(call -> Flux.fromIterable(table.values()));
    }

    void battery(final Double soc, final boolean stale) {
        battery(soc, 4800.0, stale);
    }

    void battery(final Double soc, final Double power, final boolean stale) {
        when(batteryService.battery()).thenReturn(Mono.just(new BatteryReply(soc, 99.0, power, null, null, null,
            null, null, null, null, null, ChargeSamples.NOW, stale, "local")));
    }

    void grid(final Double power) {
        when(gridService.grid()).thenReturn(Mono.just(new GridReply(null, power, null, null, null, ChargeSamples.NOW,
            false)));
    }

    void moveTo(final LocalDateTime time) {
        clock.moveBy(Duration.between(clock.instant(), time.atZone(SolaxSamples.HOUSE).toInstant()));
    }

    BatteryChargeRunEntity only() {
        return table.values().iterator().next();
    }
}
