package cloud.cholewa.energy.api;

import cloud.cholewa.energy.api.model.GridDirection;
import cloud.cholewa.energy.api.model.GridReply;
import cloud.cholewa.energy.api.model.PhaseReply;
import cloud.cholewa.energy.api.model.SourceStatusReply;
import cloud.cholewa.energy.config.ExceptionHandlerConfig;
import cloud.cholewa.energy.meter.GridService;
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
}
