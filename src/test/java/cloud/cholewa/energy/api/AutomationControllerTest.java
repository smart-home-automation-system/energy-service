package cloud.cholewa.energy.api;

import cloud.cholewa.energy.api.model.AutomationReply;
import cloud.cholewa.energy.api.model.AutomationRequest;
import cloud.cholewa.energy.api.model.SheddingRequest;
import cloud.cholewa.energy.automation.AutomationService;
import cloud.cholewa.energy.config.ExceptionHandlerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

import static org.mockito.Mockito.when;

//The settings of the automation (HAS-338, HAS-523) - the JSON is the contract of the dashboard, so the body is written
//out whole
@WebFluxTest(AutomationController.class)
@Import(ExceptionHandlerConfig.class)
class AutomationControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private AutomationService automationService;

    //HAS-338, the contract of the energy panel: the switches always written, the time of the last change left out before
    //one; HAS-523 the switch of the load shedding
    @Test
    void should_answer_the_settings_of_the_automation() {
        when(automationService.settings()).thenReturn(Mono.just(new AutomationReply(false, 100, 5.0, 60, null, true)));

        webTestClient.get().uri("/automation")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"chargeBeforeOutage": false, "targetSoc": 100, "chargePowerKw": 5.0, "marginMinutes": 60,
                 "loadShedding": true}""",
                JsonCompareMode.STRICT);
    }

    @Test
    void should_replace_the_settings_of_the_automation() {
        when(automationService.replace(new AutomationRequest(true, 90, 4.5, 30)))
            .thenReturn(Mono.just(new AutomationReply(true, 90, 4.5, 30, LocalDateTime.of(2026, 11, 3, 15, 0), true)));

        webTestClient.put().uri("/automation")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"chargeBeforeOutage\": true, \"targetSoc\": 90, \"chargePowerKw\": 4.5, \"marginMinutes\": 30}")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.chargeBeforeOutage").isEqualTo(true);
    }

    //HAS-523: the switch of the load shedding by a request of its own
    @Test
    void should_switch_the_load_shedding() {
        when(automationService.replaceShedding(new SheddingRequest(false)))
            .thenReturn(Mono.just(new AutomationReply(false, 100, 5.0, 60, LocalDateTime.of(2026, 11, 28, 12, 0), false)));

        webTestClient.put().uri("/automation/shedding")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"enabled\": false}")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.loadShedding").isEqualTo(false);
    }
}
