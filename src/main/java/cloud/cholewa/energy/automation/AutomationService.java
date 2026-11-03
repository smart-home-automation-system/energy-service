package cloud.cholewa.energy.automation;

import cloud.cholewa.energy.api.model.AutomationReply;
import cloud.cholewa.energy.api.model.AutomationRequest;
import cloud.cholewa.energy.database.EnergyAutomationEntity;
import cloud.cholewa.energy.database.EnergyAutomationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

//The settings of the automatic charge before a planned outage (HAS-338): the one row of energy_automation, read and
//replaced whole. Turning the switch off cancels no charge already running - the panel cancels it with DELETE
@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationService {

    private final EnergyAutomationRepository repository;
    private final Clock clock;

    public Mono<AutomationReply> settings() {
        return read().map(AutomationService::reply);
    }

    public Mono<AutomationReply> replace(final AutomationRequest request) {
        return Mono.fromRunnable(() -> AutomationRules.check(request.chargeBeforeOutage(), request.targetSoc(),
                request.chargePowerKw(), request.marginMinutes()))
            .then(read())
            .flatMap(held -> repository.save(held
                .withChargeBeforeOutage(request.chargeBeforeOutage())
                .withTargetSoc(request.targetSoc())
                .withChargePowerKw(request.chargePowerKw())
                .withMarginMinutes(request.marginMinutes())
                .withUpdatedAt(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS))))
            .doOnNext(saved -> log.info("The automatic charge before an outage set: {}, to {} %, {} kW, margin {} min",
                saved.chargeBeforeOutage() ? "on" : "off", saved.targetSoc(), saved.chargePowerKw(), saved.marginMinutes()))
            .map(AutomationService::reply);
    }

    //the row the migration inserted - a database without it is a failed read, never a default
    Mono<EnergyAutomationEntity> read() {
        return repository.findById(EnergyAutomationEntity.ROW_ID)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("energy_automation has no row")));
    }

    static AutomationReply reply(final EnergyAutomationEntity row) {
        return new AutomationReply(row.chargeBeforeOutage(), row.targetSoc(), row.chargePowerKw(), row.marginMinutes(),
            row.updatedAt());
    }
}
