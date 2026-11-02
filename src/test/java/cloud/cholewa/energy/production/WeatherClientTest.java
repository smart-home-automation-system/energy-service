package cloud.cholewa.energy.production;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//the illuminance from weather-service (HAS-333), against a stub: the metric as /current answers it, none without it, an
//error a failed call
class WeatherClientTest {

    private MockWebServer server;
    private WeatherClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new WeatherClient(WebClient.builder().baseUrl("http://localhost:" + server.getPort()).build());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build();
    }

    @Test
    void should_read_the_illuminance_of_the_station() throws InterruptedException {
        server.enqueue(json("""
            {"illuminance":{"value":24500.0,"measuredAt":"2026-10-28T12:00:12","stale":false},
             "temperature":{"value":12.3,"measuredAt":"2026-10-28T12:00:03","stale":false}}"""));

        client.illuminance().as(StepVerifier::create)
            .expectNext(new WeatherClient.Illuminance(24500.0, LocalDateTime.of(2026, 10, 28, 12, 0, 12), false))
            .verifyComplete();

        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/home/weather/current");
    }

    @Test
    void should_answer_nothing_without_the_metric_or_its_value() {
        server.enqueue(json("{\"temperature\":{\"value\":12.3}}"));
        server.enqueue(json("{\"illuminance\":{\"stale\":true}}"));

        client.illuminance().as(StepVerifier::create).verifyComplete();
        client.illuminance().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_fail_on_an_error_of_weather_service() {
        server.enqueue(new MockResponse.Builder().code(503).build());

        client.illuminance().as(StepVerifier::create)
            .expectErrorMessage("weather-service answered 503")
            .verify();
    }
}
