package cloud.cholewa.energy.solax;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;

import static cloud.cholewa.energy.solax.SolaxSamples.EVENING;
import static cloud.cholewa.energy.solax.SolaxSamples.JSON;
import static cloud.cholewa.energy.solax.SolaxSamples.sample;
import static cloud.cholewa.energy.solax.SolaxSamples.solax;
import static org.assertj.core.api.Assertions.assertThat;

class SolaxClientTest {

    private MockWebServer server;
    private SolaxClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new SolaxClient(WebClient.builder(), JSON);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private String address() {
        return "localhost:" + server.getPort();
    }

    //the dongle answers text/html: the body is parsed by the service, not by the codecs
    @Test
    void should_ask_the_dongle_for_its_real_time_data_with_the_registration_number() throws InterruptedException {
        server.enqueue(html(sample(EVENING)));

        client.read(solax(address())).as(StepVerifier::create)
            .assertNext(reading -> assertThat(reading.battery().soc()).isEqualTo(64.0))
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/");
        assertThat(request.getHeaders().get("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(request.getBody().utf8()).isEqualTo("optType=ReadRealTimeData&pwd=" + SolaxSamples.REGISTRATION);
    }

    @Test
    void should_fail_on_a_refused_registration_number() {
        server.enqueue(html("0:code,message:\"failed\"}"));

        client.read(solax(address())).as(StepVerifier::create)
            .expectError(RegistrationRefusedException.class)
            .verify();
    }

    @Test
    void should_fail_on_an_answer_without_a_body() {
        server.enqueue(html(""));

        client.read(solax(address())).as(StepVerifier::create)
            .expectErrorMessage("The dongle answered without a body")
            .verify();
    }

    @Test
    void should_fail_on_an_error_status() {
        server.enqueue(new MockResponse.Builder().code(500).body("busy").build());

        client.read(solax(address())).as(StepVerifier::create)
            .expectErrorMessage("The dongle answered 500")
            .verify();
    }

    //a closed port is a failed call of the same kind, never another exception out of the client
    @Test
    void should_fail_when_nothing_answers() {
        client.read(solax("localhost:1")).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(SolaxCallException.class)
                .hasMessageStartingWith("The dongle did not answer"))
            .verify();
    }

    private static MockResponse html(final String body) {
        return new MockResponse.Builder().addHeader("Content-Type", "text/html").body(body).build();
    }
}
