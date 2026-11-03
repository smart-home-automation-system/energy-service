package cloud.cholewa.energy.tauron;

import io.netty.channel.ChannelOption;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Function;

//The public API of Tauron Dystrybucja (HAS-337), the one the Home Assistant integration Eales/tauron-dystrybucja uses,
//checked against the site on 2026-11-03. No login.
//The towns come from the path enum/geo/cities by a phrase of their name of 3 letters at least, each with its GAID and
//Name. The streets of a town come from enum/geo/streets by a phrase and the GAID of the town as ownerGAID, each with its
//GAID, Name and ShortName. The outages come from outages/address by the GAIDs of the town and the street, the house
//number and a range of local times, with OutageListType, AddressPoint, IdsWWW and OutageItems, read by Announcement.
//The JSON is sometimes served as text/plain, so the body is read as text. A new connection for every call, 5 s to connect
//and 5 s for an answer; nothing retried - the next read comes within the hour. Built from the injected builder: traced
//and logged by Logbook
@Component
public class TauronClient {

    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    static final int CONNECT_TIMEOUT_MILLIS = 5000;
    //an answer of a whole area for 30 days is about 40 kB, a quarter of a year about 80 kB
    static final int MAX_ANSWER_BYTES = 2 * 1024 * 1024;
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
    private static final String PART_NAME = "partName";
    private static final String TRUE = "true";

    private final WebClient webClient;
    private final JsonMapper jsonMapper;

    public TauronClient(
        final WebClient.Builder webClientBuilder,
        final JsonMapper jsonMapper,
        final TauronProperties properties
    ) {
        final HttpClient httpClient = HttpClient.newConnection()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
            .responseTimeout(RESPONSE_TIMEOUT);
        this.webClient = webClientBuilder.clone()
            .baseUrl(properties.baseUrl().toString())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_ANSWER_BYTES))
            .build();
        this.jsonMapper = jsonMapper;
    }

    public Mono<JsonNode> cities(final String name) {
        return get(uri -> uri.path("/waapi/enum/geo/cities").queryParam(PART_NAME, name).build());
    }

    public Mono<JsonNode> streets(final String name, final long cityGaid) {
        return get(uri -> uri.path("/waapi/enum/geo/streets")
            .queryParam(PART_NAME, name)
            .queryParam("ownerGAID", cityGaid)
            .build());
    }

    public Mono<JsonNode> outages(final TauronAddress address, final LocalDateTime from, final LocalDateTime to) {
        return get(uri -> uri.path("/waapi/outages/address")
            .queryParam("cityGAID", address.cityGaid())
            .queryParam("streetGAID", address.streetGaid())
            .queryParam("houseNo", address.houseNo())
            .queryParam("fromDate", LOCAL.format(from))
            .queryParam("toDate", LOCAL.format(to))
            .queryParam("getLightingSupport", TRUE)
            .queryParam("getServicedSwitchingoff", TRUE)
            .build());
    }

    private Mono<JsonNode> get(final Function<UriBuilder, URI> uri) {
        return webClient.get()
            .uri(uri)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response ->
                Mono.just(new TauronException("Tauron answered HTTP " + response.statusCode().value())))
            .bodyToMono(String.class)
            .switchIfEmpty(Mono.error(() -> new TauronException("Tauron answered without a body")))
            .map(this::tree)
            .onErrorMap(failure -> !(failure instanceof TauronException),
                failure -> new TauronException("Tauron did not answer: " + failure.getClass().getSimpleName()));
    }

    private JsonNode tree(final String body) {
        try {
            return jsonMapper.readTree(body);
        } catch (final RuntimeException notJson) {
            throw new TauronException("Tauron answered something that is not JSON");
        }
    }
}
