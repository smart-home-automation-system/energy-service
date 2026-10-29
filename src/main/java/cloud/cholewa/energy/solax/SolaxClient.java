package cloud.cholewa.energy.solax;

import cloud.cholewa.energy.registry.Inverter;
import io.netty.channel.ChannelOption;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

//The local API of the dongle of the SOLAX (HAS-328): POST / with optType=ReadRealTimeData and the registration number
//of the dongle as pwd, form-encoded. Built from the injected builder, so the call is traced and logged by Logbook;
//3 s to connect, 5 s for an answer, nothing retried - the next read comes with the next interval. Any failure, a refused number
//included, is an error of the Mono (SolaxCallException); the reader decides what is logged
@Component
public class SolaxClient {

    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    static final int CONNECT_TIMEOUT_MILLIS = 3000;

    private final WebClient webClient;
    private final JsonMapper jsonMapper;

    public SolaxClient(final WebClient.Builder webClientBuilder, final JsonMapper jsonMapper) {
        //a new connection for every read: the dongle is a small device, and a kept-alive socket it dropped in the
        //meantime would fail the next read
        final HttpClient httpClient = HttpClient.newConnection()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
            .responseTimeout(RESPONSE_TIMEOUT);
        this.webClient = webClientBuilder.clone()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
        this.jsonMapper = jsonMapper;
    }

    public Mono<SolaxReading> read(final Inverter inverter) {
        return webClient.post()
            .uri("http://" + inverter.address() + "/")
            .body(BodyInserters.fromFormData("optType", "ReadRealTimeData")
                .with("pwd", inverter.registrationNumber()))
            .retrieve()
            .onStatus(HttpStatusCode::isError, response ->
                Mono.just(new SolaxCallException("The dongle answered " + response.statusCode().value())))
            .bodyToMono(String.class)
            .defaultIfEmpty("")
            .map(body -> Type33.parse(body, jsonMapper))
            .onErrorMap(failure -> !(failure instanceof SolaxCallException),
                failure -> new SolaxCallException("The dongle did not answer: " + failure));
    }
}
