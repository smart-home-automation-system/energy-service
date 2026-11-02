package cloud.cholewa.energy.sofar;

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
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

//the client of the Solarman cloud (HAS-330) against a stub, with the answers the cloud gave on 2026-11-02: the token held,
//taken anew a day before it expires and once when a call says it is invalid; every answer judged by success; a failure
//never quotes the body
class SolarmanClientTest {

    private static final String TOKEN = """
        {"access_token":"%s","token_type":"bearer","refresh_token":"r","expires_in":"5183999","scope":null,"uid":"1",
         "code":null,"msg":null,"success":true,"requestId":"a"}""";

    private final SolaxSamples.MovableClock clock = new SolaxSamples.MovableClock();
    private MockWebServer server;
    private SolarmanClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new SolarmanClient(WebClient.builder(), SolaxSamples.JSON,
            SofarSamples.properties(URI.create("http://localhost:" + server.getPort())), clock);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build();
    }

    private static MockResponse token(final String value) {
        return json(TOKEN.formatted(value));
    }

    @Test
    void should_take_a_token_and_read_the_device_with_it() throws InterruptedException {
        server.enqueue(token("first"));
        server.enqueue(json(SofarSamples.sample()));

        client.currentData(SofarSamples.SERIAL).as(StepVerifier::create)
            .assertNext(answer -> {
                assertThat(answer.path("deviceSn").asString()).isEqualTo(SofarSamples.SERIAL);
                assertThat(answer.path("dataList").size()).isGreaterThan(100);
            })
            .verifyComplete();

        final RecordedRequest tokenRequest = server.takeRequest();
        assertThat(tokenRequest.getMethod()).isEqualTo("POST");
        assertThat(tokenRequest.getUrl().encodedPath()).isEqualTo("/account/v1.0/token");
        assertThat(tokenRequest.getUrl().queryParameter("appId")).isEqualTo("app");
        assertThat(tokenRequest.getUrl().queryParameter("language")).isEqualTo("en");
        assertThat(tokenRequest.getHeaders().get("Content-Type")).startsWith("application/json");
        assertThat(SolaxSamples.JSON.readTree(tokenRequest.getBody().utf8())).isEqualTo(SolaxSamples.JSON.readTree("""
            {"appSecret":"secret","email":"owner@example.com",
             "password":"5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8"}"""));
        final RecordedRequest read = server.takeRequest();
        assertThat(read.getMethod()).isEqualTo("POST");
        assertThat(read.getUrl().encodedPath()).isEqualTo("/device/v1.0/currentData");
        assertThat(read.getHeaders().get("Authorization")).isEqualTo("bearer first");
        assertThat(read.getBody().utf8()).isEqualTo("{\"deviceSn\":\"SH1234567890ABCDEFGH\"}");
    }

    //the token is valid 60 days (expires_in "5183999", a string): held, and taken anew a day before it expires
    @Test
    void should_hold_the_token_and_take_a_new_one_a_day_before_it_expires() throws InterruptedException {
        server.enqueue(token("first"));
        server.enqueue(json(SofarSamples.sample()));
        server.enqueue(json(SofarSamples.sample()));
        server.enqueue(token("second"));
        server.enqueue(json(SofarSamples.sample()));

        client.currentData(SofarSamples.SERIAL).as(StepVerifier::create).expectNextCount(1).verifyComplete();
        clock.moveBy(Duration.ofDays(58).plusHours(23));
        client.currentData(SofarSamples.SERIAL).as(StepVerifier::create).expectNextCount(1).verifyComplete();
        clock.moveBy(Duration.ofHours(1));
        client.currentData(SofarSamples.SERIAL).as(StepVerifier::create).expectNextCount(1).verifyComplete();

        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/account/v1.0/token");
        assertThat(server.takeRequest().getHeaders().get("Authorization")).isEqualTo("bearer first");
        assertThat(server.takeRequest().getHeaders().get("Authorization")).isEqualTo("bearer first");
        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/account/v1.0/token");
        assertThat(server.takeRequest().getHeaders().get("Authorization")).isEqualTo("bearer second");
    }

    //2101019 "auth invalid token" in an HTTP 200 takes a new token, once
    @Test
    void should_take_a_new_token_when_the_cloud_says_it_is_invalid() throws InterruptedException {
        server.enqueue(token("first"));
        server.enqueue(json("""
            {"code":"2101019","msg":"auth invalid token","success":false,"requestId":"b"}"""));
        server.enqueue(token("second"));
        server.enqueue(json(SofarSamples.sample()));

        client.currentData(SofarSamples.SERIAL).as(StepVerifier::create).expectNextCount(1).verifyComplete();

        server.takeRequest();
        server.takeRequest();
        server.takeRequest();
        assertThat(server.takeRequest().getHeaders().get("Authorization")).isEqualTo("bearer second");
    }

    //a second refusal of a new token is a failure, not a loop
    @Test
    void should_fail_when_the_new_token_is_refused_too() {
        server.enqueue(token("first"));
        server.enqueue(json("""
            {"code":"2101019","msg":"auth invalid token","success":false}"""));
        server.enqueue(token("second"));
        server.enqueue(json("""
            {"code":"2101019","msg":"auth invalid token","success":false}"""));

        client.currentData(SofarSamples.SERIAL).as(StepVerifier::create)
            .expectErrorMessage("The Solarman cloud refused a new token")
            .verify();
        assertThat(server.getRequestCount()).isEqualTo(4);
    }

    @Test
    void should_take_a_new_token_after_an_http_401() {
        server.enqueue(token("first"));
        server.enqueue(new MockResponse.Builder().code(401).build());
        server.enqueue(token("second"));
        server.enqueue(json(SofarSamples.sample()));

        client.currentData(SofarSamples.SERIAL).as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }

    //refused credentials (2101025): the code alone, never the body
    @Test
    void should_fail_with_the_code_alone_when_the_token_is_refused() {
        server.enqueue(json("""
            {"access_token":null,"code":"2101025","msg":"auth failed","success":false}"""));

        client.currentData(SofarSamples.SERIAL).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(SolarmanException.class)
                .hasMessage("The Solarman cloud gave no token: code 2101025"))
            .verify();
    }

    //2101016 "device no upload records found" - a device the cloud does not know
    @Test
    void should_fail_with_the_code_alone_when_a_call_fails() {
        server.enqueue(token("first"));
        server.enqueue(json("""
            {"code":"2101016","msg":"device no upload records found","success":false}"""));

        client.currentData(SofarSamples.SERIAL).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(SolarmanException.class)
                .hasMessage("The Solarman cloud answered code 2101016"))
            .verify();
    }

    @Test
    void should_fail_on_an_http_error_or_an_answer_that_is_not_json() {
        server.enqueue(token("first"));
        server.enqueue(new MockResponse.Builder().code(502).body("<html>bad gateway</html>").build());
        server.enqueue(json("<html>maintenance</html>"));

        client.currentData(SofarSamples.SERIAL).as(StepVerifier::create)
            .expectErrorMessage("The Solarman cloud answered HTTP 502")
            .verify();
        client.currentData(SofarSamples.SERIAL).as(StepVerifier::create)
            .expectErrorMessage("The Solarman cloud answered something that is not JSON")
            .verify();
    }

    //a token answer without expires_in is used once and taken anew with the next call
    @Test
    void should_take_a_new_token_when_the_answer_gives_no_expiry() throws InterruptedException {
        server.enqueue(json("""
            {"access_token":"first","success":true}"""));
        server.enqueue(json(SofarSamples.sample()));
        server.enqueue(token("second"));
        server.enqueue(json(SofarSamples.sample()));

        client.currentData(SofarSamples.SERIAL).as(StepVerifier::create).expectNextCount(1).verifyComplete();
        client.currentData(SofarSamples.SERIAL).as(StepVerifier::create).expectNextCount(1).verifyComplete();

        server.takeRequest();
        assertThat(server.takeRequest().getHeaders().get("Authorization")).isEqualTo("bearer first");
        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/account/v1.0/token");
        assertThat(server.takeRequest().getHeaders().get("Authorization")).isEqualTo("bearer second");
    }
}
