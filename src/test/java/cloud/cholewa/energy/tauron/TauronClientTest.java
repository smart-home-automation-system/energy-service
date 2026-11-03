package cloud.cholewa.energy.tauron;

import cloud.cholewa.energy.solax.SolaxSamples;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//the client of the public API of Tauron (HAS-337) against a stub: the paths and parameters of the Home Assistant
//integration, JSON served as text/plain, a failure never quoting the body
class TauronClientTest {

    private MockWebServer server;
    private TauronClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new TauronClient(WebClient.builder(), SolaxSamples.JSON,
            TauronSamples.properties(URI.create("http://localhost:" + server.getPort())));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static MockResponse text(final String body) {
        return new MockResponse.Builder().addHeader("Content-Type", "text/plain; charset=utf-8").body(body).build();
    }

    @Test
    void should_read_the_outages_of_the_address_served_as_text() throws InterruptedException {
        server.enqueue(text(TauronSamples.area()));

        client.outages(new TauronAddress(105, 751, "1a"), LocalDateTime.of(2026, 11, 3, 15, 0, 7),
                LocalDateTime.of(2026, 12, 3, 15, 0, 7))
            .as(StepVerifier::create)
            .assertNext(answer -> assertThat(answer.path("OutageItems").size()).isEqualTo(8))
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/waapi/outages/address");
        assertThat(request.getUrl().queryParameter("cityGAID")).isEqualTo("105");
        assertThat(request.getUrl().queryParameter("streetGAID")).isEqualTo("751");
        assertThat(request.getUrl().queryParameter("houseNo")).isEqualTo("1a");
        assertThat(request.getUrl().queryParameter("fromDate")).isEqualTo("2026-11-03T15:00:07");
        assertThat(request.getUrl().queryParameter("toDate")).isEqualTo("2026-12-03T15:00:07");
        assertThat(request.getUrl().queryParameter("getLightingSupport")).isEqualTo("true");
        assertThat(request.getUrl().queryParameter("getServicedSwitchingoff")).isEqualTo("true");
    }

    @Test
    void should_look_up_the_town_and_its_streets() throws InterruptedException {
        server.enqueue(text("[{\"GAID\":105,\"Name\":\"Testowo\"}]"));
        server.enqueue(text("[{\"GAID\":751,\"Name\":\"Przykładowa\"}]"));

        client.cities("Testowo").as(StepVerifier::create).expectNextCount(1).verifyComplete();
        client.streets("Przykładowa", 105).as(StepVerifier::create).expectNextCount(1).verifyComplete();

        final RecordedRequest cities = server.takeRequest();
        assertThat(cities.getUrl().encodedPath()).isEqualTo("/waapi/enum/geo/cities");
        assertThat(cities.getUrl().queryParameter("partName")).isEqualTo("Testowo");
        final RecordedRequest streets = server.takeRequest();
        assertThat(streets.getUrl().encodedPath()).isEqualTo("/waapi/enum/geo/streets");
        assertThat(streets.getUrl().queryParameter("partName")).isEqualTo("Przykładowa");
        assertThat(streets.getUrl().queryParameter("ownerGAID")).isEqualTo("105");
    }

    @Test
    void should_fail_an_error_status_without_its_body() {
        server.enqueue(new MockResponse.Builder().code(503).body("{\"secret\":\"body\"}").build());

        client.cities("Testowo").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(TauronException.class)
                .hasMessage("Tauron answered HTTP 503"))
            .verify();
    }

    @Test
    void should_fail_an_answer_that_is_not_json() {
        server.enqueue(text("<html>maintenance</html>"));

        client.cities("Testowo").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(TauronException.class)
                .hasMessage("Tauron answered something that is not JSON"))
            .verify();
    }

    @Test
    void should_fail_an_answer_without_a_body() {
        server.enqueue(new MockResponse.Builder().code(200).build());

        client.cities("Testowo").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).hasMessage("Tauron answered without a body"))
            .verify();
    }
}
