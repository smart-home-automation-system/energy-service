package cloud.cholewa.energy.forecast;

import cloud.cholewa.energy.production.LocationProperties;
import cloud.cholewa.energy.solax.SolaxSamples;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

//the client of Open-Meteo (HAS-371) against a stub: the parameters of the plane, the times as unix seconds turned into the
//start of the hour each value covers in the house time - across a change of the clocks too - and every failure with a
//fixed message
class OpenMeteoClientTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final ForecastProperties.Plane ROOF = new ForecastProperties.Plane(35.0, 0.0, 8.24);

    private MockWebServer server;
    private OpenMeteoClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        final ForecastProperties properties = new ForecastProperties(URI.create("http://localhost:" + server.getPort()),
            Duration.ofHours(1), ROOF, new ForecastProperties.Plane(37.0, 0.0, 5.0));
        client = new OpenMeteoClient(WebClient.builder(), SolaxSamples.JSON, properties,
            new LocationProperties(50.0, 19.5), Clock.fixed(Instant.parse("2026-11-04T09:00:00Z"), HOUSE));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static long epoch(final LocalDateTime houseTime) {
        return houseTime.atZone(HOUSE).toEpochSecond();
    }

    private static String answer(final String time, final String irradiance, final String horizontal,
                                 final String cloud, final String temperature, final String snowfall) {
        return """
            {"latitude":50.0,"longitude":19.5,"utc_offset_seconds":3600,"timezone":"Europe/Warsaw",
             "hourly":{"time":[%s],"global_tilted_irradiance":[%s],"shortwave_radiation":[%s],"cloud_cover":[%s],
             "temperature_2m":[%s],"snowfall":[%s]}}""".formatted(time, irradiance, horizontal, cloud, temperature, snowfall);
    }

    private void enqueue(final String body) {
        server.enqueue(new MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build());
    }

    @Test
    void should_ask_the_plane_and_store_each_value_at_the_start_of_its_hour() throws InterruptedException {
        final LocalDateTime eleven = LocalDateTime.of(2026, 11, 4, 11, 0);
        enqueue(answer(epoch(eleven) + "," + epoch(eleven.plusHours(1)), "68.6,null", "74,58", "100,99", "4.9,5.0",
            "0,0.1"));

        client.forecast(ROOF)
            .as(StepVerifier::create)
            .assertNext(hours -> assertThat(hours).containsExactly(
                new HourForecast(LocalDateTime.of(2026, 11, 4, 10, 0), 68.6, 74.0, 100.0, 4.9, 0.0),
                new HourForecast(LocalDateTime.of(2026, 11, 4, 11, 0), null, 58.0, 99.0, 5.0, 0.1)))
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/v1/forecast");
        assertThat(request.getUrl().queryParameter("latitude")).isEqualTo("50.0");
        assertThat(request.getUrl().queryParameter("longitude")).isEqualTo("19.5");
        assertThat(request.getUrl().queryParameter("hourly"))
            .isEqualTo("global_tilted_irradiance,shortwave_radiation,cloud_cover,temperature_2m,snowfall");
        assertThat(request.getUrl().queryParameter("tilt")).isEqualTo("35.0");
        assertThat(request.getUrl().queryParameter("azimuth")).isEqualTo("0.0");
        assertThat(request.getUrl().queryParameter("forecast_days")).isEqualTo("7");
        assertThat(request.getUrl().queryParameter("timeformat")).isEqualTo("unixtime");
        assertThat(request.getUrl().queryParameter("timezone")).isEqualTo("Europe/Warsaw");
    }

    //the model answers -1 W/m2 at night now and then (2026-11-03): no radiation is negative, a temperature is
    @Test
    void should_read_a_radiation_below_zero_as_none() {
        final LocalDateTime night = LocalDateTime.of(2026, 11, 6, 21, 0);
        enqueue(answer(String.valueOf(epoch(night)), "-0.4", "-1", "100", "-0.5", "0"));

        client.forecast(ROOF)
            .as(StepVerifier::create)
            .assertNext(hours -> assertThat(hours).containsExactly(
                new HourForecast(night.minusHours(1), 0.0, 0.0, 100.0, -0.5, 0.0)))
            .verifyComplete();
    }

    //2026-10-25: at 03:00 summer time the clocks went back to 02:00 - the two hours from 02:00 are two hours apart in
    //unix seconds, and the hour after the change is 03:00 winter time
    @Test
    void should_take_the_hours_of_a_change_of_the_clocks_by_their_unix_seconds() {
        final long backAt = Instant.parse("2026-10-25T01:00:00Z").getEpochSecond();
        enqueue(answer(backAt + "," + (backAt + 3600) + "," + (backAt + 7200), "0,0,0", "0,0,0", "1,2,3", "1,2,3",
            "0,0,0"));

        client.forecast(ROOF)
            .as(StepVerifier::create)
            .assertNext(hours -> assertThat(hours).extracting(HourForecast::hour).containsExactly(
                LocalDateTime.of(2026, 10, 25, 2, 0),
                LocalDateTime.of(2026, 10, 25, 2, 0),
                LocalDateTime.of(2026, 10, 25, 3, 0)))
            .verifyComplete();
    }

    @Test
    void should_fail_on_an_error_of_open_meteo_without_its_reason() {
        server.enqueue(new MockResponse.Builder().code(400).addHeader("Content-Type", "application/json")
            .body("{\"reason\":\"Parameter `&tilt=` must be within 0° and 90°\",\"error\":true}").build());

        client.forecast(ROOF)
            .as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(OpenMeteoException.class)
                .hasMessage("Open-Meteo answered HTTP 400"))
            .verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not json",
        "{}",
        "{\"hourly\":{\"time\":[]}}",
        "{\"hourly\":{\"time\":[1793000000],\"global_tilted_irradiance\":[1]}}",
        "{\"hourly\":{\"time\":[1793000000],\"global_tilted_irradiance\":[1,2],\"shortwave_radiation\":[1],"
            + "\"cloud_cover\":[1],\"temperature_2m\":[1],\"snowfall\":[0]}}",
        "{\"hourly\":{\"time\":[\"2026-11-04T10:00\"],\"global_tilted_irradiance\":[1],\"shortwave_radiation\":[1],"
            + "\"cloud_cover\":[1],\"temperature_2m\":[1],\"snowfall\":[0]}}",
        "{\"hourly\":{\"time\":[1793000000],\"global_tilted_irradiance\":[\"sunny\"],\"shortwave_radiation\":[1],"
            + "\"cloud_cover\":[1],\"temperature_2m\":[1],\"snowfall\":[0]}}"})
    void should_fail_on_an_answer_that_is_not_the_forecast(final String body) {
        enqueue(body);

        client.forecast(ROOF)
            .as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(OpenMeteoException.class)
                .hasMessageStartingWith("Open-Meteo answered")
                .hasMessageNotContaining("sunny"))
            .verify();
    }

    @Test
    void should_fail_when_open_meteo_does_not_answer() {
        server.close();

        client.forecast(ROOF)
            .as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(OpenMeteoException.class)
                .hasMessageStartingWith("Open-Meteo did not answer: "))
            .verify();
    }
}
