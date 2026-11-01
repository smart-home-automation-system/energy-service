package cloud.cholewa.energy.solaxcloud;

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

//the client of the SolaX cloud (HAS-329) against a stub: the token held, taken anew a day before it expires and once
//when a call says it is invalid; every answer judged by its code; a failure never quotes the body
class SolaxCloudClientTest {

    private static final String TOKEN = """
        {"code":0,"result":{"access_token":"%s","expires_in":2592000}}""";

    private final SolaxSamples.MovableClock clock = new SolaxSamples.MovableClock();
    private MockWebServer server;
    private SolaxCloudClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new SolaxCloudClient(WebClient.builder(), SolaxSamples.JSON,
            CloudSamples.properties(URI.create("http://localhost:" + server.getPort())), clock);
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
    void should_take_a_token_and_read_the_inverter_with_it() throws InterruptedException {
        server.enqueue(token("first"));
        server.enqueue(json(CloudSamples.sample("inverter")));

        client.inverter(SolaxSamples.SERIAL).as(StepVerifier::create)
            .assertNext(inverter -> {
                assertThat(inverter.deviceSn()).isEqualTo(SolaxSamples.SERIAL);
                assertThat(inverter.acPower1()).isEqualTo(187.0);
                assertThat(inverter.mpptTotalInputPower()).isEqualTo(1073.0);
                assertThat(inverter.mpptMap()).containsEntry("MPPT1Power", 772.0);
                assertThat(inverter.plantLocalTime()).isEqualTo("2026-11-01 12:52:37");
            })
            .verifyComplete();

        final RecordedRequest tokenRequest = server.takeRequest();
        assertThat(tokenRequest.getUrl().encodedPath()).isEqualTo("/openapi/auth/oauth/token");
        assertThat(tokenRequest.getHeaders().get("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(tokenRequest.getBody().utf8())
            .isEqualTo("client_id=client&client_secret=secret&grant_type=client_credentials");
        final RecordedRequest read = server.takeRequest();
        assertThat(read.getHeaders().get("Authorization")).isEqualTo("bearer first");
        assertThat(read.getUrl().encodedPath()).isEqualTo("/openapi/v2/device/realtime_data");
        assertThat(read.getUrl().queryParameter("snList")).isEqualTo(SolaxSamples.SERIAL);
        assertThat(read.getUrl().queryParameter("deviceType")).isEqualTo("1");
        assertThat(read.getUrl().queryParameter("businessType")).isEqualTo("1");
    }

    //DoD: the token held while it is valid, taken anew a day before it expires (30 days)
    @Test
    void should_hold_the_token_and_take_a_new_one_a_day_before_it_expires() throws InterruptedException {
        server.enqueue(token("first"));
        server.enqueue(json(CloudSamples.sample("battery")));
        server.enqueue(json(CloudSamples.sample("battery")));
        server.enqueue(token("second"));
        server.enqueue(json(CloudSamples.sample("battery")));

        client.battery(SolaxSamples.SERIAL).as(StepVerifier::create).expectNextCount(1).verifyComplete();
        clock.moveBy(Duration.ofDays(28).plusHours(23));
        client.battery(SolaxSamples.SERIAL).as(StepVerifier::create).expectNextCount(1).verifyComplete();
        clock.moveBy(Duration.ofHours(1));
        client.battery(SolaxSamples.SERIAL).as(StepVerifier::create)
            .assertNext(battery -> {
                assertThat(battery.batterySOC()).isEqualTo(9.0);
                assertThat(battery.chargeDischargePower()).isEqualTo(146.0);
                assertThat(battery.batteryCycleTimes()).isEqualTo(134);
            })
            .verifyComplete();

        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/openapi/auth/oauth/token");
        assertThat(server.takeRequest().getHeaders().get("Authorization")).isEqualTo("bearer first");
        assertThat(server.takeRequest().getHeaders().get("Authorization")).isEqualTo("bearer first");
        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/openapi/auth/oauth/token");
        final RecordedRequest last = server.takeRequest();
        assertThat(last.getHeaders().get("Authorization")).isEqualTo("bearer second");
        assertThat(last.getUrl().queryParameter("snList")).isEqualTo(SolaxSamples.SERIAL);
        assertThat(last.getUrl().queryParameter("deviceType")).isEqualTo("2");
        assertThat(last.getUrl().queryParameter("requestSnType")).isEqualTo("1");
    }

    //DoD: the answer of an invalid token (10402, in an HTTP 200, the token quoted back) takes a new one, once
    @Test
    void should_take_a_new_token_when_the_cloud_says_it_is_invalid() throws InterruptedException {
        server.enqueue(token("first"));
        server.enqueue(json("""
            {"code":10402,"message":"Invalid access token: first"}"""));
        server.enqueue(token("second"));
        server.enqueue(json(CloudSamples.sample("plant")));

        client.plant().as(StepVerifier::create)
            .assertNext(plant -> assertThat(plant.dailyYield()).isEqualTo(2.5))
            .verifyComplete();

        server.takeRequest();
        server.takeRequest();
        server.takeRequest();
        final RecordedRequest retried = server.takeRequest();
        assertThat(retried.getHeaders().get("Authorization")).isEqualTo("bearer second");
        assertThat(retried.getUrl().queryParameter("plantId")).isEqualTo(CloudSamples.PLANT);
    }

    @Test
    void should_take_a_new_token_on_an_http_401() {
        server.enqueue(token("first"));
        server.enqueue(new MockResponse.Builder().code(401).build());
        server.enqueue(token("second"));
        server.enqueue(json(CloudSamples.sample("alarms-open")));

        client.alarms(true).as(StepVerifier::create)
            .assertNext(page -> assertThat(page.records()).isEmpty())
            .verifyComplete();
    }

    //a token refused again is a failure, not a loop
    @Test
    void should_fail_when_the_new_token_is_refused_too() {
        server.enqueue(token("first"));
        server.enqueue(json("""
            {"code":10402,"message":"Invalid access token: first"}"""));
        server.enqueue(token("second"));
        server.enqueue(json("""
            {"code":10402,"message":"Invalid access token: second"}"""));

        client.plant().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOf(SolaxCloudException.class)
                .hasMessage("The SolaX cloud refused a new token"))
            .verify();
        assertThat(server.getRequestCount()).isEqualTo(4);
    }

    //the code alone - the message of the cloud may quote what it was sent
    @Test
    void should_fail_with_the_code_alone() {
        server.enqueue(token("first"));
        server.enqueue(json("""
            {"code":10500,"message":"something about client secret"}"""));

        client.plant().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOf(SolaxCloudException.class)
                .hasMessage("The SolaX cloud answered code 10500"))
            .verify();
    }

    @Test
    void should_fail_on_refused_credentials_without_quoting_them() {
        server.enqueue(json("""
            {"code":10400,"message":"Bad client credentials","success":false}"""));

        client.plant().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOf(SolaxCloudException.class)
                .hasMessage("The SolaX cloud gave no token: code 10400"))
            .verify();
    }

    @Test
    void should_fail_on_an_answer_that_is_not_json() {
        server.enqueue(token("first"));
        server.enqueue(json("<html>busy</html>"));

        client.plant().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOf(SolaxCloudException.class)
                .hasMessage("The SolaX cloud answered something that is not JSON"))
            .verify();
    }

    @Test
    void should_fail_when_the_serial_is_not_in_the_answer() {
        server.enqueue(token("first"));
        server.enqueue(json("""
            {"code":10000,"result":[]}"""));

        client.inverter("H0000000000000").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .hasMessage("The SolaX cloud has no data of H0000000000000"))
            .verify();
    }

    //DoD: a closed alarm of the house decoded
    @Test
    void should_read_the_closed_alarms() throws InterruptedException {
        server.enqueue(token("first"));
        server.enqueue(json(CloudSamples.sample("alarms-closed")));

        client.alarms(false).as(StepVerifier::create)
            .assertNext(page -> {
                assertThat(page.total()).isEqualTo(50);
                assertThat(page.records().getFirst()).isEqualTo(new CloudAlarm("Bat Power Low", "17", "1", 2, 0,
                    "2026-10-23 10:12:36", "2026-10-23 11:23:46", SolaxSamples.SERIAL));
            })
            .verifyComplete();
        server.takeRequest();
        assertThat(server.takeRequest().getUrl().queryParameter("alarmState")).isEqualTo("0");
    }

    //prepared for the charge task: the body is the caller's, sent as JSON with the token
    @Test
    void should_send_the_request_status_query_as_json() throws InterruptedException {
        server.enqueue(token("first"));
        server.enqueue(json("""
            {"code":10000,"result":[{"status":4}]}"""));

        client.requestStatus("r-1").as(StepVerifier::create)
            .assertNext(result -> assertThat(result.get(0).path("status").asInt()).isEqualTo(4))
            .verifyComplete();
        server.takeRequest();
        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/openapi/apiRequestLog/listByCondition");
        assertThat(request.getBody().utf8()).isEqualTo("{\"requestId\":\"r-1\"}");
    }

    //two calls at once take one token between them
    @Test
    void should_take_one_token_for_calls_at_once() {
        server.enqueue(token("first"));
        server.enqueue(json(CloudSamples.sample("plant")));
        server.enqueue(json(CloudSamples.sample("plant")));

        reactor.core.publisher.Flux.merge(client.plant(), client.plant()).as(StepVerifier::create)
            .expectNextCount(2)
            .verifyComplete();

        assertThat(server.getRequestCount()).isEqualTo(3);
    }
}
