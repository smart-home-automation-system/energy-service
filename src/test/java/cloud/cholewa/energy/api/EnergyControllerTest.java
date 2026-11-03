package cloud.cholewa.energy.api;

import cloud.cholewa.energy.alert.AlertService;
import cloud.cholewa.energy.api.model.AlertReply;
import cloud.cholewa.energy.api.model.BatteryDirection;
import cloud.cholewa.energy.api.model.BatteryReply;
import cloud.cholewa.energy.api.model.DayReply;
import cloud.cholewa.energy.api.model.FlowsReply;
import cloud.cholewa.energy.api.model.HistoryReply;
import cloud.cholewa.energy.api.model.MonthsReply;
import cloud.cholewa.energy.api.model.GridDirection;
import cloud.cholewa.energy.api.model.InverterReply;
import cloud.cholewa.energy.api.model.GridReply;
import cloud.cholewa.energy.api.model.PhaseReply;
import cloud.cholewa.energy.api.model.PvReply;
import cloud.cholewa.energy.api.model.GridStateReply;
import cloud.cholewa.energy.api.model.OutageReply;
import cloud.cholewa.energy.api.model.SourceStatusReply;
import cloud.cholewa.energy.api.model.StatusReply;
import cloud.cholewa.energy.config.ExceptionHandlerConfig;
import cloud.cholewa.energy.error.EnergyException;
import cloud.cholewa.energy.days.DaysService;
import cloud.cholewa.energy.flows.FlowsService;
import cloud.cholewa.energy.history.HistoryService;
import cloud.cholewa.energy.inverter.BatteryService;
import cloud.cholewa.energy.inverter.InverterService;
import cloud.cholewa.energy.meter.GridService;
import cloud.cholewa.energy.outage.GridOutageService;
import cloud.cholewa.energy.solax.RunMode;
import cloud.cholewa.energy.status.EnergyStatusService;
import cloud.cholewa.energy.tauron.PlannedOutageService;
import cloud.cholewa.energy.charge.ChargeService;
import cloud.cholewa.energy.automation.AutomationService;
import cloud.cholewa.energy.api.model.AutomationReply;
import cloud.cholewa.energy.api.model.AutomationRequest;
import cloud.cholewa.energy.api.model.ChargeReply;
import cloud.cholewa.energy.api.model.ChargeRequest;
import cloud.cholewa.energy.api.model.ChargeRunReply;
import cloud.cholewa.energy.api.model.PlannedOutageReply;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @MockitoBean
    private FlowsService flowsService;
    @MockitoBean
    private HistoryService historyService;
    @MockitoBean
    private DaysService daysService;
    @MockitoBean
    private GridOutageService gridOutageService;
    @MockitoBean
    private AlertService alertService;
    @MockitoBean
    private PlannedOutageService plannedOutageService;
    @MockitoBean
    private ChargeService chargeService;
    @MockitoBean
    private AutomationService automationService;

    private static ChargeRunReply chargingRun() {
        return new ChargeRunReply(7L, "charging", "dashboard", LocalDateTime.of(2026, 11, 3, 14, 5, 30), 100,
            LocalDateTime.of(2026, 11, 3, 14, 5), LocalDateTime.of(2026, 11, 3, 18, 0), 4,
            LocalDateTime.of(2026, 11, 3, 14, 5, 50), 45.0, null, null, null, null, null, null);
    }

    //HAS-336, the contract of the energy panel (HAS-342): the fields of the charge going on, written out whole
    @Test
    void should_answer_the_charge_going_on() {
        when(chargeService.current()).thenReturn(Mono.just(new ChargeReply(true, chargingRun(), 62.5, 4800.0, 81L,
            LocalDateTime.of(2026, 11, 12, 1, 37))));

        webTestClient.get().uri("/battery/charge")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"active": true,
                 "run": {"id": 7, "state": "charging", "source": "dashboard", "requestedAt": "2026-11-03T14:05:30",
                         "targetSoc": 100, "windowStart": "2026-11-03T14:05:00", "windowEnd": "2026-11-03T18:00:00",
                         "deliveryStatus": 4, "deliveredAt": "2026-11-03T14:05:50", "socAtStart": 45.0},
                 "soc": 62.5, "power": 4800.0, "minutesLeft": 81, "plannedStart": "2026-11-12T01:37:00"}""",
                JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_no_charge_as_inactive() {
        when(chargeService.current()).thenReturn(Mono.just(new ChargeReply(false, null, null, null, null, null)));

        webTestClient.get().uri("/battery/charge")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("{\"active\": false}", JsonCompareMode.STRICT);
    }

    //the source defaults to the dashboard; {} takes the defaults of the body
    @Test
    void should_start_a_charge_and_answer_202() {
        when(chargeService.start(new ChargeRequest(80, LocalDateTime.of(2026, 11, 3, 16, 0)), "automation"))
            .thenReturn(Mono.just(chargingRun()));
        when(chargeService.start(new ChargeRequest(null, null), "dashboard")).thenReturn(Mono.just(chargingRun()));

        webTestClient.post().uri("/battery/charge?source=automation")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("{\"targetSoc\": 80, \"until\": \"2026-11-03T16:00:00\"}")
            .exchange()
            .expectStatus().isAccepted()
            .expectBody().jsonPath("$.state").isEqualTo("charging");
        webTestClient.post().uri("/battery/charge")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isAccepted();
    }

    @Test
    void should_answer_a_second_charge_with_its_code() {
        when(chargeService.start(new ChargeRequest(null, null), "dashboard"))
            .thenReturn(Mono.error(EnergyException.chargeInProgress()));

        webTestClient.post().uri("/battery/charge")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("CHARGE_IN_PROGRESS");
    }

    @Test
    void should_cancel_a_charge_and_answer_202() {
        when(chargeService.cancel()).thenReturn(Mono.just(chargingRun()));

        webTestClient.delete().uri("/battery/charge")
            .exchange()
            .expectStatus().isAccepted();
    }

    @Test
    void should_answer_the_runs_of_a_range() {
        when(chargeService.runs(LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2026, 11, 4, 0, 0)))
            .thenReturn(Mono.just(List.of(chargingRun())));

        webTestClient.get().uri("/battery/charge/runs?from=2026-11-01T00:00:00&to=2026-11-04T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$[0].id").isEqualTo(7);
    }

    //HAS-337: notifiedAt left out until a notice was published, skipAutomation always written
    @Test
    void should_answer_the_planned_outages() {
        when(plannedOutageService.planned()).thenReturn(Mono.just(List.of(
            new PlannedOutageReply(3L, LocalDateTime.of(2026, 11, 12, 8, 0), LocalDateTime.of(2026, 11, 12, 13, 0),
                "planned", "street", "Testowo ul. Przykładowa", LocalDateTime.of(2026, 11, 3, 15, 0),
                LocalDateTime.of(2026, 11, 3, 15, 1), false, LocalDateTime.of(2026, 11, 12, 1, 37)),
            new PlannedOutageReply(4L, LocalDateTime.of(2026, 11, 18, 8, 0), LocalDateTime.of(2026, 11, 18, 14, 0),
                "planned", "town", "Testowo ul. Lipowa", LocalDateTime.of(2026, 11, 3, 15, 0), null, false, null))));

        webTestClient.get().uri("/outages/planned")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [
                  {"id": 3, "startsAt": "2026-11-12T08:00:00", "endsAt": "2026-11-12T13:00:00", "kind": "planned",
                   "scope": "street", "description": "Testowo ul. Przykładowa", "firstSeenAt": "2026-11-03T15:00:00",
                   "notifiedAt": "2026-11-03T15:01:00", "skipAutomation": false,
                   "plannedChargeStart": "2026-11-12T01:37:00"},
                  {"id": 4, "startsAt": "2026-11-18T08:00:00", "endsAt": "2026-11-18T14:00:00", "kind": "planned",
                   "scope": "town", "description": "Testowo ul. Lipowa", "firstSeenAt": "2026-11-03T15:00:00",
                   "skipAutomation": false}
                ]""", JsonCompareMode.STRICT);
    }

    //HAS-338, the contract of the energy panel: the switch always written, the time of the last change left out before one
    @Test
    void should_answer_the_settings_of_the_automation() {
        when(automationService.settings()).thenReturn(Mono.just(new AutomationReply(false, 100, 5.0, 60, null)));

        webTestClient.get().uri("/automation")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"chargeBeforeOutage": false, "targetSoc": 100, "chargePowerKw": 5.0, "marginMinutes": 60}""",
                JsonCompareMode.STRICT);
    }

    @Test
    void should_replace_the_settings_of_the_automation() {
        when(automationService.replace(new AutomationRequest(true, 90, 4.5, 30)))
            .thenReturn(Mono.just(new AutomationReply(true, 90, 4.5, 30, LocalDateTime.of(2026, 11, 3, 15, 0))));

        webTestClient.put().uri("/automation")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("{\"chargeBeforeOutage\": true, \"targetSoc\": 90, \"chargePowerKw\": 4.5, \"marginMinutes\": 30}")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.chargeBeforeOutage").isEqualTo(true);
    }

    @Test
    void should_skip_and_allow_again_an_outage() {
        final PlannedOutageReply skipped = new PlannedOutageReply(3L, LocalDateTime.of(2026, 11, 12, 8, 0),
            LocalDateTime.of(2026, 11, 12, 13, 0), "planned", "street", "Testowo", LocalDateTime.of(2026, 11, 3, 15, 0),
            null, true, null);
        when(plannedOutageService.skip(3L, true)).thenReturn(Mono.just(skipped));
        when(plannedOutageService.skip(3L, false)).thenReturn(Mono.error(EnergyException.plannedOutageNotFound(3L)));

        webTestClient.put().uri("/outages/planned/3/skip")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.skipAutomation").isEqualTo(true);
        webTestClient.put().uri("/outages/planned/3/unskip")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_PLANNED_OUTAGE");
    }

    //HAS-341: the open alarms - the detail of an alarm written out, left out for the other kinds
    @Test
    void should_answer_the_open_alarms() {
        when(alertService.alerts()).thenReturn(Mono.just(List.of(
            new AlertReply("sofar", "alarm", LocalDateTime.of(2026, 11, 2, 12, 0), "GridOVP"),
            new AlertReply("phase b", "overload", LocalDateTime.of(2026, 11, 2, 10, 0), null))));

        webTestClient.get().uri("/alerts")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [
                  {"source": "sofar", "kind": "alarm", "openedAt": "2026-11-02T12:00:00", "detail": "GridOVP"},
                  {"source": "phase b", "kind": "overload", "openedAt": "2026-11-02T10:00:00"}
                ]""", JsonCompareMode.STRICT);
    }

    //a source not known is reachable null, written out - never false; its last reading is left out. The grid as the
    //SOLAX sees it next to the sources (HAS-334)
    @Test
    void should_answer_every_source_with_whether_it_answers() {
        when(energyStatusService.reply()).thenReturn(Mono.just(new StatusReply(List.of(
            new SourceStatusReply("meter", true, LocalDateTime.of(2026, 10, 27, 11, 0, 5)),
            new SourceStatusReply("solax", false, null),
            new SourceStatusReply("sofar", null, null),
            new SourceStatusReply("solaxCloud", null, null)
        ), new GridStateReply("lost", LocalDateTime.of(2026, 10, 31, 19, 42)))));

        webTestClient.get().uri("/status")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {
                  "sources": [
                    {"source": "meter", "reachable": true, "lastReadingAt": "2026-10-27T11:00:05"},
                    {"source": "solax", "reachable": false},
                    {"source": "sofar", "reachable": null},
                    {"source": "solaxCloud", "reachable": null}
                  ],
                  "grid": {"state": "lost", "since": "2026-10-31T19:42:00"}
                }""", JsonCompareMode.STRICT);
    }

    //after a start, before two reads of the SOLAX agree: written out as null, never read as present
    @Test
    void should_answer_a_grid_not_known_as_null() {
        when(energyStatusService.reply()).thenReturn(Mono.just(new StatusReply(List.of(),
            GridStateReply.notKnown())));

        webTestClient.get().uri("/status")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"sources": [], "grid": {"state": null}}""", JsonCompareMode.STRICT);
    }

    //HAS-334: the outages of a range, one still open - its end left out
    @Test
    void should_answer_the_outages_of_a_range() {
        when(gridOutageService.outages(LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 11, 1, 0, 0)))
            .thenReturn(Mono.just(List.of(
                new OutageReply(LocalDateTime.of(2026, 10, 4, 3, 10), LocalDateTime.of(2026, 10, 4, 5, 2), 80.0,
                    52.0, 52.0),
                new OutageReply(LocalDateTime.of(2026, 10, 31, 19, 42), null, 64.0, null, 61.0))));

        webTestClient.get().uri("/outages?from=2026-10-01T00:00:00&to=2026-11-01T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [
                  {"startedAt": "2026-10-04T03:10:00", "endedAt": "2026-10-04T05:02:00", "socAtStart": 80.0,
                   "socAtEnd": 52.0, "lowestSoc": 52.0},
                  {"startedAt": "2026-10-31T19:42:00", "socAtStart": 64.0, "lowestSoc": 61.0}
                ]""", JsonCompareMode.STRICT);
    }

    @Test
    void should_refuse_a_range_of_outages_it_cannot_answer() {
        when(gridOutageService.outages(LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2026, 10, 1, 0, 0)))
            .thenReturn(Mono.error(EnergyException.invalidRange("from must be before to, at most 366 days apart")));

        webTestClient.get().uri("/outages?from=2026-11-01T00:00:00&to=2026-10-01T00:00:00")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("INVALID_RANGE");
    }

    @Test
    void should_refuse_outages_without_a_range() {
        webTestClient.get().uri("/outages?from=2026-11-01T00:00:00")
            .exchange()
            .expectStatus().isBadRequest();
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
            new InverterReply("solax", 134L, "SOLAX inverter", "garage", RunMode.PERMANENT_FAULT, null, null, 1755.0, phases,
                1282.0, pv, 1804.0, 50.01, 0.95, 48.0, 38.6, 4510.6, LocalDateTime.of(2026, 10, 27, 20, 49), false,
                "local"),
            new InverterReply("sofar", 135L, "SOFAR inverter", "boiler", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, true, null))));

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
                   "totalYield": 4510.6, "measuredAt": "2026-10-27T20:49:00", "stale": false, "source": "local"},
                  {"id": "sofar", "registryId": 135, "name": "SOFAR inverter", "room": "boiler", "stale": true}
                ]""", JsonCompareMode.STRICT);
    }

    //HAS-330: the SOFAR with its state and faults in words - an empty list when it has none - and source solarman-cloud
    @Test
    void should_answer_the_sofar_with_its_state_and_faults() {
        final Map<String, PvReply> pv = new LinkedHashMap<>();
        pv.put("pv1", new PvReply(356.0, 0.77, 270.0));
        pv.put("pv2", new PvReply(0.0, 0.0, 0.0));
        when(inverterService.inverter("sofar")).thenReturn(Mono.just(new InverterReply("sofar", 135L,
            "SOFAR inverter", "boiler", null, "Grid connected", List.of(), 270.0, null, 200.0, pv, null, 49.99, null,
            32.0, 0.26, 5421.5, LocalDateTime.of(2026, 11, 2, 10, 19, 18), false, "solarman-cloud")));

        webTestClient.get().uri("/inverters/sofar")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"id": "sofar", "registryId": 135, "name": "SOFAR inverter", "room": "boiler",
                 "state": "Grid connected", "faults": [], "acPower": 270.0, "pvPower": 200.0,
                 "pv": {"pv1": {"voltage": 356.0, "current": 0.77, "power": 270.0},
                        "pv2": {"voltage": 0.0, "current": 0.0, "power": 0.0}},
                 "frequency": 49.99, "temperature": 32.0, "dailyYield": 0.26, "totalYield": 5421.5,
                 "measuredAt": "2026-11-02T10:19:18", "stale": false, "source": "solarman-cloud"}""",
                JsonCompareMode.STRICT);
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
            LocalDateTime.of(2026, 10, 27, 20, 49), false, "local")));

        webTestClient.get().uri("/battery")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"soc": 64.0, "health": 99.0, "power": -1980.0, "direction": "discharging", "voltage": 52.3,
                 "current": -38.6, "temperature": 20.0, "cycles": 132, "remainingKwh": 16.96, "chargedToday": 26.6,
                 "dischargedToday": 10.2, "measuredAt": "2026-10-27T20:49:00", "stale": false,
                 "source": "local"}""", JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_only_stale_for_a_battery_not_read() {
        when(batteryService.battery()).thenReturn(Mono.just(new BatteryReply(null, null, null, null, null, null, null,
            null, null, null, null, null, true, null)));

        webTestClient.get().uri("/battery")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"stale": true}""", JsonCompareMode.STRICT);
    }

    //HAS-331: the balance now - an input not known leaves out what depends on it; every input with its stale written out
    @Test
    void should_answer_the_flows() {
        final Map<String, FlowsReply.Input> inputs = new LinkedHashMap<>();
        inputs.put("grid", new FlowsReply.Input(LocalDateTime.of(2026, 11, 2, 12, 0), false));
        inputs.put("sofar", new FlowsReply.Input(null, true));
        when(flowsService.flows()).thenReturn(Mono.just(new FlowsReply(null, 3000.0, null, -1500.0, 800.0, null,
            new FlowsReply.Today(null, new BigDecimal("11.20"), new BigDecimal("1.50"), null, null, null, null, null),
            inputs)));

        webTestClient.get().uri("/flows")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"solaxPv": 3000.0, "gridPower": -1500.0, "batteryPower": 800.0,
                 "today": {"imported": 11.20, "exported": 1.50},
                 "inputs": {"grid": {"measuredAt": "2026-11-02T12:00:00", "stale": false}, "sofar": {"stale": true}}}""",
                JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_the_history_of_a_metric() {
        final LocalDateTime from = LocalDateTime.of(2026, 11, 2, 0, 0);
        when(historyService.history("house-load", from, from.plusDays(1))).thenReturn(Mono.just(new HistoryReply(
            "house-load", from, from.plusDays(1), 300, List.of(new HistoryReply.Point(from, new BigDecimal("500.00"),
            null, null)))));

        webTestClient.get().uri("/history?metric=house-load&from=2026-11-02T00:00:00&to=2026-11-03T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"metric": "house-load", "from": "2026-11-02T00:00:00", "to": "2026-11-03T00:00:00",
                 "bucketSeconds": 300, "points": [{"at": "2026-11-02T00:00:00", "avg": 500.00}]}""",
                JsonCompareMode.STRICT);
    }

    @Test
    void should_refuse_a_metric_not_known_with_its_code() {
        when(historyService.history(eq("wind"), any(),
            any())).thenReturn(Mono.error(EnergyException.unknownMetric(List.of("grid-power"))));

        webTestClient.get().uri("/history?metric=wind&from=2026-11-02T00:00:00&to=2026-11-03T00:00:00")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("UNKNOWN_METRIC")
            .jsonPath("$.errors[0].details").isEqualTo("The metric is one of grid-power");
    }

    @Test
    void should_answer_the_closed_days() {
        final LocalDate day = LocalDate.of(2026, 11, 1);
        when(daysService.days(day, day)).thenReturn(Mono.just(List.of(new DayReply(day, 4.0, 1.0, 5.0, 11.2, 0.0, 1.0,
            0.2, 15.4, 1.0, 0.727, null, null, null))));

        webTestClient.get().uri("/days?from=2026-11-01&to=2026-11-01")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [{"day": "2026-11-01", "solaxYield": 4.0, "sofarYield": 1.0, "production": 5.0, "imported": 11.2,
                  "exported": 0.0, "charged": 1.0, "discharged": 0.2, "houseLoad": 15.4, "selfConsumption": 1.0,
                  "gridShare": 0.727}]""", JsonCompareMode.STRICT);
    }

    //the months as "yyyy-MM"; a month without a closed day without its energies
    @Test
    void should_answer_the_billing_year() {
        when(daysService.months(2026)).thenReturn(Mono.just(new MonthsReply(2026, YearMonth.of(2026, 9),
            YearMonth.of(2027, 8), new BigDecimal("0.7"), List.of(new MonthsReply.Month(YearMonth.of(2026, 10), 0, null,
            null, null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("55.00"))))));

        webTestClient.get().uri("/months?year=2026")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"year": 2026, "from": "2026-09", "to": "2027-08", "ratio": 0.7,
                 "months": [{"month": "2026-10", "days": 0, "credited": 0, "drawn": 0, "bought": 0, "expired": 0,
                             "stored": 55.00}]}""", JsonCompareMode.STRICT);
    }
}
