package cloud.cholewa.energy.config;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseServiceClientConfigTest {

    private final DatabaseServiceClientConfig config = new DatabaseServiceClientConfig();
    private final ConnectionProvider connectionProvider = config.databaseServiceConnectionProvider();
    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
        connectionProvider.dispose();
    }

    @Test
    void should_call_the_address_of_database_service() throws InterruptedException {
        server.enqueue(new MockResponse.Builder().code(200).body("ok").build());
        final WebClient client = config.databaseServiceWebClient(WebClient.builder(), connectionProvider,
            new InternalServicesProperties(URI.create("http://localhost:" + server.getPort())));

        client.get().uri("/home/device/registry").retrieve().bodyToMono(String.class).as(StepVerifier::create)
            .expectNext("ok")
            .verifyComplete();

        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/home/device/registry");
    }

    //a database-service that accepts the connection and never answers must not hold the refresh until its bound
    @Test
    void should_answer_within_five_seconds() {
        assertThat(DatabaseServiceClientConfig.RESPONSE_TIMEOUT).hasSeconds(5);
        assertThat(DatabaseServiceClientConfig.CONNECT_TIMEOUT_MILLIS).isEqualTo(5000);
    }

    //an idle connection to a restarted database-service must not be reused for ever (HAS-212)
    @Test
    void should_retire_idle_and_old_connections_of_the_pool() {
        assertThat(DatabaseServiceClientConfig.MAX_IDLE_TIME).hasMinutes(2);
        assertThat(DatabaseServiceClientConfig.MAX_LIFE_TIME).hasMinutes(30);
    }
}
