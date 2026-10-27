package cloud.cholewa.energy.api;

import cloud.cholewa.energy.api.model.SourceStatusReply;
import cloud.cholewa.energy.config.ExceptionHandlerConfig;
import cloud.cholewa.energy.status.EnergyStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.Mockito.when;

//The JSON is the contract of the dashboard, so the body is written out whole
@WebFluxTest(EnergyController.class)
@Import(ExceptionHandlerConfig.class)
class EnergyControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private EnergyStatusService energyStatusService;

    //a source not known is reachable null, written out - never false
    @Test
    void should_answer_every_source_with_whether_it_answers() {
        when(energyStatusService.status()).thenReturn(Mono.just(List.of(
            new SourceStatusReply("meter", true),
            new SourceStatusReply("solax", false),
            new SourceStatusReply("sofar", null),
            new SourceStatusReply("solaxCloud", null)
        )));

        webTestClient.get().uri("/status")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [
                  {"source": "meter", "reachable": true},
                  {"source": "solax", "reachable": false},
                  {"source": "sofar", "reachable": null},
                  {"source": "solaxCloud", "reachable": null}
                ]""", JsonCompareMode.STRICT);
    }
}
