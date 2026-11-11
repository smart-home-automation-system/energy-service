package cloud.cholewa.energy.elicznik;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;

import static cloud.cholewa.energy.elicznik.ElicznikSamples.CONSUM_2026_11_08;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.READINGS_EXPORTED;
import static cloud.cholewa.energy.elicznik.ElicznikSamples.read;
import static org.assertj.core.api.Assertions.assertThat;

//the client of the eLicznik portal (HAS-340) against a stub of the login of Tauron and of the portal on one server: the
//redirects followed with the cookies of each step, the form of Keycloak posted, the point of the meter set, a refused
//login, a blocked account and a changed login told apart - none quoting an answer or the account
class ElicznikClientTest {

    private static final String FORM_PAGE = """
        <html><body><form id="kc-form-login" onsubmit="login.disabled = true; return true;"
        action="/realms/ext/login-actions/authenticate?session_code=sc1&amp;execution=ex1&amp;client_id=cas&amp;tab_id=t1"
        method="post"><input name="username" type="email"><input name="password" type="password">
        <input type="hidden" name="credentialId"></form></body></html>""";

    private MockWebServer server;
    private ElicznikClient client;
    private String base;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        base = "http://localhost:" + server.getPort();
        client = new ElicznikClient(WebClient.builder(), JsonMapper.builder().build(), properties(base));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    static ElicznikProperties properties(final String base) {
        return new ElicznikProperties(URI.create(base + "/login"), URI.create(base), "owner@example.com", "s3cr3t",
            "1_2_3", 7, Duration.ofDays(3), new BigDecimal("0.05"), Duration.ofHours(1));
    }

    private static MockResponse redirect(final String location, final String cookie) {
        final MockResponse.Builder builder = new MockResponse.Builder().code(302).addHeader("Location", location);
        return (cookie == null ? builder : builder.addHeader("Set-Cookie", cookie)).build();
    }

    private static MockResponse page(final String body) {
        return new MockResponse.Builder().addHeader("Content-Type", "text/html; charset=UTF-8").body(body).build();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build();
    }

    //the login of 2026-11-10: the CAS endpoint of the realm, its form, the ticket back to the portal, the session cookie
    private void enqueueLogin() {
        server.enqueue(redirect("/realms/ext/protocol/cas/login?service=" + base, null));
        server.enqueue(new MockResponse.Builder().addHeader("Content-Type", "text/html")
            .addHeader("Set-Cookie", "AUTH_SESSION_ID=kc1; Path=/realms/ext/; HttpOnly")
            .body(FORM_PAGE).build());
        server.enqueue(redirect(base + "/?ticket=ST-1", "KEYCLOAK_IDENTITY=id1; Path=/realms/ext/"));
        server.enqueue(redirect("/", "PHPSESSID=p1; path=/; HttpOnly"));
        server.enqueue(page("<html><select name=\"site[client]\"><option value=\"1_2_3\">1</option></select></html>"));
        server.enqueue(page("<html>ok</html>"));
    }

    @Test
    void should_log_in_through_the_form_of_keycloak_and_set_the_meter() throws InterruptedException {
        enqueueLogin();

        client.login().as(StepVerifier::create).expectNextCount(1).verifyComplete();

        final RecordedRequest start = server.takeRequest();
        assertThat(start.getMethod()).isEqualTo("GET");
        assertThat(start.getUrl().encodedPath()).isEqualTo("/login");
        assertThat(start.getUrl().queryParameter("service")).isEqualTo(base);
        assertThat(start.getHeaders().get("User-Agent")).isEqualTo(ElicznikClient.USER_AGENT);
        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/realms/ext/protocol/cas/login");
        final RecordedRequest form = server.takeRequest();
        assertThat(form.getMethod()).isEqualTo("POST");
        assertThat(form.getUrl().encodedPath()).isEqualTo("/realms/ext/login-actions/authenticate");
        assertThat(form.getUrl().queryParameter("execution")).isEqualTo("ex1");
        assertThat(form.getHeaders().get("Cookie")).contains("AUTH_SESSION_ID=kc1");
        assertThat(form.getBody().utf8())
            .isEqualTo("username=owner%40example.com&password=s3cr3t&credentialId=");
        final RecordedRequest ticket = server.takeRequest();
        assertThat(ticket.getMethod()).isEqualTo("GET");
        assertThat(ticket.getUrl().queryParameter("ticket")).isEqualTo("ST-1");
        assertThat(server.takeRequest().getHeaders().get("Cookie")).contains("PHPSESSID=p1");
        final RecordedRequest meter = server.takeRequest();
        assertThat(meter.getMethod()).isEqualTo("POST");
        assertThat(meter.getUrl().encodedPath()).isEqualTo("/ustaw_punkt");
        assertThat(meter.getHeaders().get("Cookie")).contains("PHPSESSID=p1");
        assertThat(meter.getBody().utf8()).isEqualTo("site%5Bclient%5D=1_2_3");
    }

    @Test
    void should_read_the_hours_and_the_registers_with_the_session() throws InterruptedException {
        enqueueLogin();
        server.enqueue(json(read(CONSUM_2026_11_08)));
        server.enqueue(json(read(READINGS_EXPORTED)));

        client.login()
            .flatMap(session -> client.hours(session, LocalDate.of(2026, 11, 8), false)
                .zipWith(client.registers(session, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 9), true)))
            .as(StepVerifier::create)
            .assertNext(both -> {
                assertThat(both.getT1()).hasSize(24);
                assertThat(both.getT2()).hasSize(9);
            })
            .verifyComplete();

        for (int login = 0; login < 6; login++) {
            server.takeRequest();
        }
        final RecordedRequest hours = server.takeRequest();
        assertThat(hours.getUrl().encodedPath()).isEqualTo("/energia/api");
        assertThat(hours.getHeaders().get("Cookie")).contains("PHPSESSID=p1");
        assertThat(hours.getBody().utf8())
            .isEqualTo("from=08.11.2026&to=08.11.2026&profile=full+time&type=consum&energy=1");
        final RecordedRequest registers = server.takeRequest();
        assertThat(registers.getUrl().encodedPath()).isEqualTo("/odczyty/api");
        assertThat(registers.getBody().utf8()).isEqualTo("from=01.11.2026&to=09.11.2026&type=energia-oddana");
    }

    //a wrong password answers the form again
    @Test
    void should_tell_a_refused_login() {
        server.enqueue(redirect("/realms/ext/protocol/cas/login?service=" + base, null));
        server.enqueue(page(FORM_PAGE));
        server.enqueue(page(FORM_PAGE.replace("<form", "<span>Nieprawidłowy login lub hasło</span><form")));

        client.login().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(ElicznikException.class)
                .hasMessage("The eLicznik portal refused the login - the password expired or the account changed"))
            .verify();
    }

    @Test
    void should_tell_a_blocked_account() {
        server.enqueue(redirect("/realms/ext/protocol/cas/login?service=" + base, null));
        server.enqueue(page(FORM_PAGE));
        server.enqueue(redirect(base + "/blokada", null));
        server.enqueue(page("<html>blokada</html>"));

        client.login().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .hasMessage("The eLicznik portal blocked the account - too many logins"))
            .verify();
    }

    @Test
    void should_tell_a_login_without_its_form() {
        server.enqueue(page("<html>a new login of Tauron</html>"));

        client.login().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .hasMessage("The eLicznik login form was not found - the login of Tauron changed"))
            .verify();
    }

    @Test
    void should_tell_an_error_of_the_portal_without_its_answer() {
        server.enqueue(new MockResponse.Builder().code(500).body("stack trace of the portal").build());

        client.login().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .isInstanceOf(ElicznikException.class)
                .hasMessage("The eLicznik portal answered HTTP 500 to /login"))
            .verify();
    }

    @Test
    void should_stop_a_loop_of_redirects() {
        for (int hop = 0; hop <= ElicznikClient.MAX_REDIRECTS + 1; hop++) {
            server.enqueue(redirect("/login", null));
        }

        client.login().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure)
                .hasMessage("The eLicznik portal redirected more than " + ElicznikClient.MAX_REDIRECTS + " times"))
            .verify();
    }
}
