package cloud.cholewa.energy.api;

import cloud.cholewa.energy.api.model.BatteryDirection;
import cloud.cholewa.energy.api.model.BatteryReply;
import cloud.cholewa.energy.api.model.GridDirection;
import cloud.cholewa.energy.api.model.InverterReply;
import cloud.cholewa.energy.api.model.GridReply;
import cloud.cholewa.energy.api.model.PhaseReply;
import cloud.cholewa.energy.api.model.PvReply;
import cloud.cholewa.energy.api.model.SourceStatusReply;
import cloud.cholewa.energy.config.ExceptionHandlerConfig;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.inverter.BatteryService;
import cloud.cholewa.energy.inverter.InverterService;
import cloud.cholewa.energy.meter.GridService;
import cloud.cholewa.energy.solax.RunMode;
import cloud.cholewa.energy.status.EnergyStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;

//The JSON is the contract of the dashboard, so the body is written out whole
@WebFluxTest(EnergyController.class)
@Import(ExceptionHandlerConfig.class)
class EnergyControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private EnergyStatusService energyStatusService;
    @MockitoBean
    private GridService gridService;
    @MockitoBean
    private InverterService inverterService;
    @MockitoBean
    private BatteryService batteryService;

    //a source not known is reachable null, written out - never false; its last reading is left out
    @Test
    void should_answer_every_source_with_whether_it_answers() {
        when(energyStatusService.status()).thenReturn(Mono.just(List.of(
            new SourceStatusReply("meter", true, LocalDateTime.of(2026, 10, 27, 11, 0, 5)),
            new SourceStatusReply("solax", false, null),
            new SourceStatusReply("sofar", null, null),
            new SourceStatusReply("solaxCloud", null, null)
        )));

        webTestClient.get().uri("/status")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [
                  {"source": "meter", "reachable": true, "lastReadingAt": "2026-10-27T11:00:05"},
                  {"source": "solax", "reachable": false},
                  {"source": "sofar", "reachable": null},
                  {"source": "solaxCloud", "reachable": null}
                ]""", JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_the_grid_meter() {
        final Map<String, PhaseReply> phases = new LinkedHashMap<>();
        phases.put("a", new PhaseReply(236.8, 1.652, -171.4, 391.2, 0.44, 50.0));
        phases.put("b", new PhaseReply(235.4, 1.918, 31.2, 451.5, 0.07, 50.0));
        phases.put("c", new PhaseReply(234.6, 2.431, 26.7, 570.3, 0.05, 49.9));
        when(gridService.grid()).thenReturn(Mono.just(new GridReply(phases, -113.5, 891.01175, 2158.8515,
            GridDirection.EXPORT, LocalDateTime.of(2026, 10, 27, 11, 0, 5), false)));

        webTestClient.get().uri("/grid")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {
                  "phases": {
                    "a": {"voltage": 236.8, "current": 1.652, "activePower": -171.4, "apparentPower": 391.2,
                          "powerFactor": 0.44, "frequency": 50.0},
                    "b": {"voltage": 235.4, "current": 1.918, "activePower": 31.2, "apparentPower": 451.5,
                          "powerFactor": 0.07, "frequency": 50.0},
                    "c": {"voltage": 234.6, "current": 2.431, "activePower": 26.7, "apparentPower": 570.3,
                          "powerFactor": 0.05, "frequency": 49.9}
                  },
                  "totalActivePower": -113.5,
                  "importEnergy": 891.01175,
                  "exportEnergy": 2158.8515,
                  "direction": "export",
                  "measuredAt": "2026-10-27T11:00:05",
                  "stale": false
                }""", JsonCompareMode.STRICT);
    }

    //before the first readings: only that it is stale - nothing is written as 0, a value of a phase not sent is
    //left out
    @Test
    void should_leave_out_what_the_meter_did_not_send() {
        when(gridService.grid()).thenReturn(Mono.just(new GridReply(null, null, null, null, null, null, true)));

        webTestClient.get().uri("/grid")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"stale": true}""", JsonCompareMode.STRICT);
    }

    @Test
    void should_leave_out_a_value_of_a_phase_the_meter_did_not_send() {
        when(gridService.grid()).thenReturn(Mono.just(new GridReply(
            Map.of("a", new PhaseReply(236.8, null, -171.4, null, null, null)), null, null, null, null,
            LocalDateTime.of(2026, 10, 27, 11, 0, 5), false)));

        webTestClient.get().uri("/grid")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"phases": {"a": {"voltage": 236.8, "activePower": -171.4}},
                 "measuredAt": "2026-10-27T11:00:05", "stale": false}""", JsonCompareMode.STRICT);
    }

    //HAS-328: the SOLAX with its reading, the SOFAR with its registry entry until its task
    @Test
    void should_answer_the_inverters() {
        final Map<String, PhaseReply> phases = new LinkedHashMap<>();
        phases.put("a", new PhaseReply(234.0, 2.1, 479.0, null, null, null));
        phases.put("b", new PhaseReply(232.7, 3.6, 817.0, null, null, null));
        phases.put("c", new PhaseReply(234.5, 2.1, 460.0, null, null, null));
        final Map<String, PvReply> pv = new LinkedHashMap<>();
        pv.put("pv1", new PvReply(642.1, 1.4, 897.0));
        pv.put("pv2", new PvReply(272.8, 1.4, 385.0));
        when(inverterService.inverters()).thenReturn(Mono.just(List.of(
            new InverterReply("solax", 134L, "SOLAX inverter", "garage", RunMode.PERMANENT_FAULT, 1755.0, phases,
                1282.0, pv, 1804.0, 50.01, 0.95, 48.0, 38.6, 4510.6, LocalDateTime.of(2026, 10, 27, 20, 49), false),
            new InverterReply("sofar", 135L, "SOFAR inverter", "boiler", null, null, null, null, null, null, null,
                null, null, null, null, null, true))));

        webTestClient.get().uri("/inverters")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [
                  {"id": "solax", "registryId": 134, "name": "SOLAX inverter", "room": "garage",
                   "runMode": "permanent fault", "acPower": 1755.0,
                   "phases": {"a": {"voltage": 234.0, "current": 2.1, "activePower": 479.0},
                              "b": {"voltage": 232.7, "current": 3.6, "activePower": 817.0},
                              "c": {"voltage": 234.5, "current": 2.1, "activePower": 460.0}},
                   "pvPower": 1282.0,
                   "pv": {"pv1": {"voltage": 642.1, "current": 1.4, "power": 897.0},
                          "pv2": {"voltage": 272.8, "current": 1.4, "power": 385.0}},
                   "gridPower": 1804.0,
                   "frequency": 50.01, "powerFactor": 0.95, "temperature": 48.0, "dailyYield": 38.6,
                   "totalYield": 4510.6, "measuredAt": "2026-10-27T20:49:00", "stale": false},
                  {"id": "sofar", "registryId": 135, "name": "SOFAR inverter", "room": "boiler", "stale": true}
                ]""", JsonCompareMode.STRICT);
    }

    @Test
    void should_refuse_an_inverter_not_registered_with_its_code() {
        when(inverterService.inverter("fronius")).thenReturn(Mono.error(EnergyException.inverterNotFound("fronius")));

        webTestClient.get().uri("/inverters/fronius")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_INVERTER")
            .jsonPath("$.errors[0].message").isEqualTo("Inverter not found")
            .jsonPath("$.errors[0].details").isEqualTo("No inverter fronius is registered");
    }

    @Test
    void should_answer_503_while_the_registry_is_not_known() {
        when(inverterService.inverters()).thenReturn(Mono.error(EnergyException.registryUnavailable()));

        webTestClient.get().uri("/inverters")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("REGISTRY_UNAVAILABLE");
    }

    @Test
    void should_answer_the_battery() {
        when(batteryService.battery()).thenReturn(Mono.just(new BatteryReply(64.0, 99.0, -1980.0,
            BatteryDirection.DISCHARGING, 52.3, -38.6, 20.0, 132, 16.96, 26.6, 10.2,
            LocalDateTime.of(2026, 10, 27, 20, 49), false)));

        webTestClient.get().uri("/battery")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"soc": 64.0, "health": 99.0, "power": -1980.0, "direction": "discharging", "voltage": 52.3,
                 "current": -38.6, "temperature": 20.0, "cycles": 132, "remainingKwh": 16.96, "chargedToday": 26.6,
                 "dischargedToday": 10.2, "measuredAt": "2026-10-27T20:49:00", "stale": false}""", JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_only_stale_for_a_battery_not_read() {
        when(batteryService.battery()).thenReturn(Mono.just(new BatteryReply(null, null, null, null, null, null, null,
            null, null, null, null, null, true)));

        webTestClient.get().uri("/battery")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"stale": true}""", JsonCompareMode.STRICT);
    }
}
