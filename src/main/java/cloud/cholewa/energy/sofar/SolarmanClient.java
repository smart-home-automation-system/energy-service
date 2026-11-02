package cloud.cholewa.energy.sofar;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

//The Solarman Open API (HAS-330), verified 2026-11-02 with the application Solarman granted the owner.
//- The token: POST /account/v1.0/token?appId=<appId>&language=en with {appSecret, email, password} - the password as its
//  lowercase SHA-256; a personal token of the account is enough for the plant (no organisation token). The answer
//  {access_token, token_type: bearer, refresh_token, expires_in: "5183999" (s, a string - 60 days), success} is held in
//  memory, taken anew a day before it expires and once when a call says it is invalid (code 2101019 in an HTTP 200, or an
//  HTTP 401); sent as "Authorization: bearer".
//- Every answer is HTTP 200 with success true or false and a code - 2101025 refused credentials, 2101019 an invalid token,
//  2101016 a device without records: a failure is a SolarmanException with the code alone, never the body.
//- Built from the injected builder: the calls are traced and logged by Logbook, whose filters mask the secret, the account,
//  the password and the tokens (LogbookConfig); 5 s to connect, 10 s for an answer, nothing retried but the one call after
//  a new token. 50 calls a minute are allowed - the reader makes one every five minutes
@Slf4j
@Component
public class SolarmanClient {

    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    static final int CONNECT_TIMEOUT_MILLIS = 5000;
    static final Duration REFRESH_BEFORE = Duration.ofDays(1);
    static final String INVALID_TOKEN = "2101019";
    private static final String LANGUAGE = "language";
    private static final String ENGLISH = "en";

    private final WebClient webClient;
    private final JsonMapper jsonMapper;
    private final SolarmanProperties properties;
    private final Clock clock;
    private final AtomicReference<Token> token = new AtomicReference<>();
    //the token being taken, shared by the calls that need one meanwhile
    private final AtomicReference<Mono<String>> taking = new AtomicReference<>();

    public SolarmanClient(
        final WebClient.Builder webClientBuilder,
        final JsonMapper jsonMapper,
        final SolarmanProperties properties,
        final Clock clock
    ) {
        //a new connection for every call: the cloud is asked every few minutes, and a pooled connection a balancer of the
        //internet closed in between would fail the next call
        final HttpClient httpClient = HttpClient.newConnection()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
            .responseTimeout(RESPONSE_TIMEOUT);
        this.webClient = webClientBuilder.clone()
            .baseUrl(properties.baseUrl().toString())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.clock = clock;
    }

    private record Token(String value, Instant expiresAt) {
    }

    //the current data of the device of this serial number, the whole answer: deviceSn, collectionTime, dataList
    public Mono<JsonNode> currentData(final String serialNumber) {
        return call(value -> webClient.post()
            .uri(uri -> uri.path("/device/v1.0/currentData").queryParam(LANGUAGE, ENGLISH).build())
            .header("Authorization", "bearer " + value)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(jsonMapper.createObjectNode().put("deviceSn", serialNumber)));
    }

    //with the token held, and once more with a new one when the cloud says it is invalid
    private Mono<JsonNode> call(final Function<String, WebClient.RequestHeadersSpec<?>> request) {
        return token()
            .flatMap(value -> exchange(request.apply(value)))
            .onErrorResume(InvalidToken.class, invalid -> Mono.fromRunnable(() -> token.set(null))
                .then(token())
                .flatMap(value -> exchange(request.apply(value))))
            .onErrorMap(InvalidToken.class, invalid -> new SolarmanException("The Solarman cloud refused a new token"));
    }

    private Mono<JsonNode> exchange(final WebClient.RequestHeadersSpec<?> request) {
        return request.retrieve()
            .onStatus(status -> status.value() == HttpStatus.UNAUTHORIZED.value(), response -> Mono.just(new InvalidToken()))
            .onStatus(HttpStatusCode::isError, response ->
                Mono.just(new SolarmanException("The Solarman cloud answered HTTP " + response.statusCode().value())))
            .bodyToMono(String.class)
            .switchIfEmpty(Mono.error(() -> new SolarmanException("The Solarman cloud answered without a body")))
            .map(this::tree)
            .flatMap(root -> Mono.just(root)
                .filter(answer -> answer.path("success").asBoolean(false))
                .switchIfEmpty(Mono.error(() -> INVALID_TOKEN.equals(root.path("code").asString(""))
                    ? new InvalidToken()
                    : new SolarmanException("The Solarman cloud answered code " + root.path("code").asString("none")))))
            .onErrorMap(failure -> !(failure instanceof SolarmanException) && !(failure instanceof InvalidToken),
                failure -> new SolarmanException("The Solarman cloud did not answer: " + failure.getClass().getSimpleName()));
    }

    private Mono<String> token() {
        return Mono.defer(() -> Optional.ofNullable(token.get())
            .filter(held -> held.expiresAt().minus(REFRESH_BEFORE).isAfter(clock.instant()))
            .map(held -> Mono.just(held.value()))
            .orElseGet(this::newToken));
    }

    private Mono<String> newToken() {
        return Mono.defer(() -> taking.updateAndGet(current -> Optional.ofNullable(current)
            .orElseGet(() -> takeToken().doFinally(signal -> taking.set(null)).cache())));
    }

    private Mono<String> takeToken() {
        return webClient.post()
            .uri(uri -> uri.path("/account/v1.0/token")
                .queryParam("appId", properties.appId())
                .queryParam(LANGUAGE, ENGLISH)
                .build())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(jsonMapper.createObjectNode()
                .put("appSecret", properties.appSecret())
                .put("email", properties.email())
                .put("password", properties.passwordSha256()))
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> Mono.just(new SolarmanException(
                "The Solarman cloud answered HTTP " + response.statusCode().value() + " for a token")))
            .bodyToMono(String.class)
            .switchIfEmpty(Mono.error(() -> new SolarmanException("The Solarman cloud answered no token")))
            .map(this::tree)
            .flatMap(root -> Mono.justOrEmpty(Optional.of(root)
                    .filter(answer -> !answer.path("access_token").asString("").isBlank())
                    .map(answer -> new Token(answer.path("access_token").asString(),
                        clock.instant().plusSeconds(seconds(answer.path("expires_in"))))))
                .switchIfEmpty(Mono.error(() -> new SolarmanException(
                    "The Solarman cloud gave no token: code " + root.path("code").asString("none")))))
            .doOnNext(held -> {
                token.set(held);
                log.info("A token of the Solarman cloud taken, valid until {}", held.expiresAt());
            })
            .map(Token::value)
            .onErrorMap(failure -> !(failure instanceof SolarmanException),
                failure -> new SolarmanException("The Solarman cloud did not answer for a token: "
                    + failure.getClass().getSimpleName()));
    }

    //expires_in comes as a string of seconds; one not given, or not a number, is no time at all - the token is used for
    //this call and taken anew with the next one
    private static long seconds(final JsonNode expiresIn) {
        try {
            return Long.parseLong(expiresIn.asString("0").trim());
        } catch (final NumberFormatException notNumber) {
            return 0;
        }
    }

    private JsonNode tree(final String body) {
        try {
            return jsonMapper.readTree(body);
        } catch (final RuntimeException notJson) {
            throw new SolarmanException("The Solarman cloud answered something that is not JSON");
        }
    }

    //a call the cloud refused for its token: tried once more with a new one
    private static final class InvalidToken extends RuntimeException {

        InvalidToken() {
            super("The token of the Solarman cloud is not valid", null, false, false);
        }
    }
}
