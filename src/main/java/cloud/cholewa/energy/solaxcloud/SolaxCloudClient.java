package cloud.cholewa.energy.solaxcloud;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

//The SolaX Developer Platform (HAS-329), verified 2026-10-23 and 2026-11-01 with the application of the owner.
//- The token: POST /openapi/auth/oauth/token form-encoded with the client credentials; the answer
//  {code, result: {access_token, expires_in (s)}} is held in memory, taken anew a day before it expires and once when a
//  call says it is invalid (code 10402 in an HTTP 200, or an HTTP 401); sent as "Authorization: bearer" (lowercase b).
//- Every answer is HTTP 200 with a code - 10000 is the success, anything else a failure (10400 bad credentials, 10402 an
//  invalid token, which quotes the token back): a failure is a SolaxCloudException with the code alone, never the body.
//- Built from the injected builder: the calls are traced and logged by Logbook, whose body filter masks the token and the
//  secret (LogbookConfig); 5 s to connect, 10 s for an answer, nothing retried but the one call after a new token.
//- The writes (HAS-336): the Self Use work mode answers {code, result: {<sn>: {status}}, requestId} - the requestId beside
//  the result, so the whole answer is handed over; what became of it is asked by the requestId (listByCondition), whose
//  answer the documentation shows as {code: 0, success: true, result: [{sn, status}]} - a success is code 10000 or
//  success true. The fields of the work mode were read in the documentation of the platform on 2026-11-03
@Slf4j
@Component
public class SolaxCloudClient {

    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    static final int CONNECT_TIMEOUT_MILLIS = 5000;
    static final Duration REFRESH_BEFORE = Duration.ofDays(1);
    static final int SUCCESS = 10000;
    static final int INVALID_TOKEN = 10402;
    private static final String BUSINESS_TYPE = "businessType";
    private static final String RESULT = "result";
    private static final String INVERTER = "1";
    private static final String BATTERY = "2";

    private final WebClient webClient;
    private final JsonMapper jsonMapper;
    private final SolaxCloudProperties properties;
    private final Clock clock;
    private final AtomicReference<Token> token = new AtomicReference<>();
    //the token being taken, shared by the calls that need one meanwhile - two requests at once could each take a token
    //and the second could make the first one invalid
    private final AtomicReference<Mono<String>> taking = new AtomicReference<>();

    public SolaxCloudClient(
        final WebClient.Builder webClientBuilder,
        final JsonMapper jsonMapper,
        final SolaxCloudProperties properties,
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

    //the realtime data of the inverter of this serial number
    public Mono<CloudInverter> inverter(final String serialNumber) {
        return get(uri -> uri.path("/openapi/v2/device/realtime_data")
            .queryParam("snList", serialNumber)
            .queryParam("deviceType", INVERTER)
            .queryParam(BUSINESS_TYPE, "1")
            .build())
            .map(result -> first(result, CloudInverter.class, serialNumber));
    }

    //the realtime data of the battery of the inverter of this serial number - asked by the inverter (requestSnType=1):
    //by its own serial number the cloud answers 10500 "no permission" (2026-11-01)
    public Mono<CloudBattery> battery(final String inverterSerialNumber) {
        return get(uri -> uri.path("/openapi/v2/device/realtime_data")
            .queryParam("snList", inverterSerialNumber)
            .queryParam("deviceType", BATTERY)
            .queryParam("requestSnType", "1")
            .queryParam(BUSINESS_TYPE, "1")
            .build())
            .map(result -> first(result, CloudBattery.class, inverterSerialNumber));
    }

    //the energy of the plant today and in total
    public Mono<CloudPlant> plant() {
        return get(uri -> uri.path("/openapi/v2/plant/realtime_data")
            .queryParam("plantId", properties.plantId())
            .queryParam(BUSINESS_TYPE, "1")
            .build())
            .map(result -> jsonMapper.treeToValue(result, CloudPlant.class));
    }

    //the first page of the alarms of the plant, the ongoing ones or the closed ones
    public Mono<CloudAlarm.Page> alarms(final boolean ongoing) {
        return get(uri -> uri.path("/openapi/v2/alarm/page_alarm_info")
            .queryParam("plantId", properties.plantId())
            .queryParam("alarmState", ongoing ? "1" : "0")
            .queryParam(BUSINESS_TYPE, "1")
            .build())
            .map(result -> jsonMapper.treeToValue(result, CloudAlarm.Page.class));
    }

    //the Self Use work mode of the inverters of the body (HAS-336): the request id of the command - an answer without one
    //is a failed call
    public Mono<String> setSelfUse(final JsonNode body) {
        return call(value -> postRequest("/openapi/v2/device/inverter_work_mode/batch_set_spontaneity_self_use", body,
            value))
            .map(root -> root.path("requestId").asString(""))
            .filter(requestId -> !requestId.isBlank())
            .switchIfEmpty(Mono.error(() -> new SolaxCloudException("The SolaX cloud answered no request id")));
    }

    //what became of a command, by its request id: the status for this device - 1 offline, 2 issuance failed, 3 issued,
    //4 received and executing, 5 execution failed, 6 timeout; empty while the log has no entry of it
    public Mono<Integer> requestStatus(final String requestId, final String serialNumber) {
        return call(value -> postRequest("/openapi/apiRequestLog/listByCondition",
                jsonMapper.createObjectNode().put("requestId", requestId), value))
            .flatMapIterable(root -> root.path(RESULT))
            .filter(entry -> serialNumber.equals(entry.path("sn").asString("")))
            .next()
            .map(entry -> entry.path("status").asInt(0))
            .filter(status -> status > 0);
    }

    private Mono<JsonNode> get(final Function<UriBuilder, URI> uri) {
        return call(value -> webClient.get().uri(uri).header("Authorization", "bearer " + value)
                .accept(MediaType.APPLICATION_JSON))
            .map(root -> root.path(RESULT));
    }

    private WebClient.RequestHeadersSpec<?> postRequest(final String path, final JsonNode body, final String token) {
        return webClient.post().uri(path).header("Authorization", "bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(body);
    }

    //with the token held, and once more with a new one when the cloud says it is invalid
    private Mono<JsonNode> call(final Function<String, WebClient.RequestHeadersSpec<?>> request) {
        return token()
            .flatMap(value -> exchange(request.apply(value)))
            .onErrorResume(InvalidToken.class, invalid -> Mono.fromRunnable(() -> token.set(null))
                .then(token())
                .flatMap(value -> exchange(request.apply(value))))
            .onErrorMap(InvalidToken.class, invalid -> new SolaxCloudException("The SolaX cloud refused a new token"));
    }

    private Mono<JsonNode> exchange(final WebClient.RequestHeadersSpec<?> request) {
        return request.retrieve()
            .onStatus(status -> status.value() == HttpStatus.UNAUTHORIZED.value(), response -> Mono.just(new InvalidToken()))
            .onStatus(HttpStatusCode::isError, response ->
                Mono.just(new SolaxCloudException("The SolaX cloud answered HTTP " + response.statusCode().value())))
            .bodyToMono(String.class)
            .switchIfEmpty(Mono.error(() -> new SolaxCloudException("The SolaX cloud answered without a body")))
            .map(this::tree)
            .flatMap(root -> switch (root.path("code").asInt(-1)) {
                case SUCCESS -> Mono.just(root);
                case INVALID_TOKEN -> Mono.error(new InvalidToken());
                default -> Mono.just(root)
                    .filter(answer -> answer.path("success").asBoolean(false))
                    .switchIfEmpty(Mono.error(() ->
                        new SolaxCloudException("The SolaX cloud answered code " + root.path("code"))));
            })
            .onErrorMap(failure -> !(failure instanceof SolaxCloudException) && !(failure instanceof InvalidToken),
                failure -> new SolaxCloudException("The SolaX cloud did not answer: " + failure.getClass().getSimpleName()));
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
            .uri("/openapi/auth/oauth/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("client_id", properties.clientId())
                .with("client_secret", properties.clientSecret())
                .with("grant_type", "client_credentials"))
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> Mono.just(new SolaxCloudException(
                "The SolaX cloud answered HTTP " + response.statusCode().value() + " for a token")))
            .bodyToMono(String.class)
            .switchIfEmpty(Mono.error(() -> new SolaxCloudException("The SolaX cloud answered no token")))
            .map(this::tree)
            .flatMap(root -> Mono.justOrEmpty(Optional.of(root.path(RESULT))
                    .filter(result -> !result.path("access_token").asString("").isBlank())
                    .map(result -> new Token(result.path("access_token").asString(),
                        clock.instant().plusSeconds(result.path("expires_in").asLong(0)))))
                .switchIfEmpty(Mono.error(() -> new SolaxCloudException(
                    "The SolaX cloud gave no token: code " + root.path("code")))))
            .doOnNext(held -> {
                token.set(held);
                log.info("A token of the SolaX cloud taken, valid until {}", held.expiresAt());
            })
            .map(Token::value)
            .onErrorMap(failure -> !(failure instanceof SolaxCloudException),
                failure -> new SolaxCloudException("The SolaX cloud did not answer for a token: "
                    + failure.getClass().getSimpleName()));
    }

    private JsonNode tree(final String body) {
        try {
            return jsonMapper.readTree(body);
        } catch (final RuntimeException notJson) {
            throw new SolaxCloudException("The SolaX cloud answered something that is not JSON");
        }
    }

    //the realtime data answers a list, one entry a serial number asked
    private <T> T first(final JsonNode result, final Class<T> type, final String serialNumber) {
        final List<JsonNode> entries = result.isArray() ? result.valueStream().toList() : List.of();
        return entries.stream().findFirst()
            .map(entry -> jsonMapper.treeToValue(entry, type))
            .orElseThrow(() -> new SolaxCloudException("The SolaX cloud has no data of " + serialNumber));
    }

    //a call the cloud refused for its token: tried once more with a new one
    private static final class InvalidToken extends RuntimeException {

        InvalidToken() {
            super("The token of the SolaX cloud is not valid", null, false, false);
        }
    }
}
