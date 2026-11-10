package cloud.cholewa.energy.elicznik;

import io.netty.channel.ChannelOption;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The eLicznik portal of Tauron Dystrybucja (HAS-340) - no official API: the requests of the Python elicznik scraper and
 * the Tauron AMIplus integration of Home Assistant (both after Tauron moved its login to Keycloak, June 2026), checked in
 * the owner's browser on 2026-11-10. A login is three steps:
 * <ol>
 *   <li>a GET of the login URL with the portal as its service - the CAS endpoint of the Keycloak realm answers its form
 *   {@code kc-form-login};</li>
 *   <li>a POST of the form action with the account and an empty credential id - back to the portal with a ticket and
 *   its session cookie;</li>
 *   <li>a POST of the point of the meter to {@code /ustaw_punkt}, as the portal does when one is chosen.</li>
 * </ol>
 * Then {@code /energia/api} answers the hours and {@code /odczyty/api} the registers, both posted form-encoded. Redirects
 * are followed here, not by the client, so the cookies of every host are kept ({@link CookieJar}); a new connection for
 * every request, nothing retried. A refused login answers the form again; a blocked account the page {@code /blokada} or
 * "Przekroczono maksymalną liczbę logowań". What fails is an {@link ElicznikException} naming the step - never an answer
 * of the portal, the account or the password.
 */
@Component
public class ElicznikClient {

    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(20);
    static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    static final int MAX_REDIRECTS = 10;
    //a page of the portal is about 100 kB
    static final int MAX_ANSWER_BYTES = 4 * 1024 * 1024;
    static final String USER_AGENT = "smart-home-automation-system energy-service (one login a day)";
    //the tags of the forms of a page, then the action of the login form - two linear patterns, no backtracking
    private static final Pattern FORM_TAG = Pattern.compile("<form\\b[^>]*+>");
    private static final Pattern ACTION = Pattern.compile("\\baction=\"([^\"]*+)\"");
    private static final String FORM_ID = "id=\"kc-form-login\"";
    private static final String BLOCKED_PAGE = "/blokada";
    private static final String TOO_MANY_LOGINS = "Przekroczono maksymaln";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT);

    private final WebClient webClient;
    private final JsonMapper jsonMapper;
    private final ElicznikProperties properties;

    public ElicznikClient(
        final WebClient.Builder webClientBuilder,
        final JsonMapper jsonMapper,
        final ElicznikProperties properties
    ) {
        final HttpClient httpClient = HttpClient.newConnection()
            .followRedirect(false)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
            .responseTimeout(RESPONSE_TIMEOUT);
        this.webClient = webClientBuilder.clone()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_ANSWER_BYTES))
            .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
            .build();
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    //a logged-in session of the portal, the point of the meter set
    public record Session(CookieJar cookies) {
    }

    //an answer at the end of its redirects
    record Page(URI uri, String body) {
    }

    public Mono<Session> login() {
        final CookieJar cookies = new CookieJar();
        final URI start = UriComponentsBuilder.fromUri(properties.loginUrl())
            .queryParam("service", properties.serviceUrl().toString())
            .build(true).toUri();
        return follow(cookies, HttpMethod.GET, start, null, 0)
            .map(page -> formAction(page).orElseThrow(() ->
                new ElicznikException("The eLicznik login form was not found - the login of Tauron changed")))
            .flatMap(action -> follow(cookies, HttpMethod.POST, action, credentials(), 0))
            .flatMap(page -> Mono.justOrEmpty(refusal(page)).<Page>flatMap(Mono::error).switchIfEmpty(Mono.just(page)))
            .filter(page -> properties.serviceUrl().getHost().equalsIgnoreCase(page.uri().getHost()))
            .switchIfEmpty(Mono.error(() -> new ElicznikException(
                "The eLicznik login did not lead back to the portal - the login of Tauron changed")))
            .flatMap(page -> follow(cookies, HttpMethod.POST, service("/ustaw_punkt"), meter(), 0))
            .thenReturn(new Session(cookies));
    }

    //the hours of a day taken from the grid ("consum") or given to it ("oze")
    public Mono<List<ElicznikAnswers.Hour>> hours(final Session session, final LocalDate day, final boolean exported) {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("from", DATE.format(day));
        form.add("to", DATE.format(day));
        form.add("profile", "full time");
        form.add("type", exported ? "oze" : "consum");
        form.add("energy", exported ? "2" : "1");
        return data(session, "/energia/api", form).map(body -> ElicznikAnswers.hours(jsonMapper, body));
    }

    //the registers of the meter at the end of each day of [from, to] - imported ("energia-pobrana") or exported
    public Mono<List<ElicznikAnswers.Register>> registers(final Session session, final LocalDate from, final LocalDate to,
                                                         final boolean exported) {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("from", DATE.format(from));
        form.add("to", DATE.format(to));
        form.add("type", exported ? "energia-oddana" : "energia-pobrana");
        return data(session, "/odczyty/api", form).map(body -> ElicznikAnswers.registers(jsonMapper, body));
    }

    private Mono<String> data(final Session session, final String path, final MultiValueMap<String, String> form) {
        return follow(session.cookies(), HttpMethod.POST, service(path), form, 0)
            .flatMap(page -> Mono.justOrEmpty(refusal(page)).<String>flatMap(Mono::error)
                .switchIfEmpty(Mono.just(page.body())));
    }

    //one request and every redirect after it, the cookies kept; a redirect of a POST becomes a GET (302 / 303)
    private Mono<Page> follow(final CookieJar cookies, final HttpMethod method, final URI uri,
                              final MultiValueMap<String, String> form, final int redirects) {
        return Mono.just(redirects)
            .filter(followed -> followed <= MAX_REDIRECTS)
            .switchIfEmpty(Mono.error(() -> new ElicznikException("The eLicznik portal redirected more than "
                + MAX_REDIRECTS + " times")))
            .flatMap(followed -> exchange(cookies, method, uri, form, followed));
    }

    private Mono<Page> exchange(final CookieJar cookies, final HttpMethod method, final URI uri,
                                final MultiValueMap<String, String> form, final int redirects) {
        final WebClient.RequestBodySpec request = webClient.method(method)
            .uri(uri)
            .headers(headers -> Optional.of(cookies.headerFor(uri)).filter(header -> !header.isEmpty())
                .ifPresent(header -> headers.set(HttpHeaders.COOKIE, header)));
        final WebClient.RequestHeadersSpec<?> withBody = form == null ? request
            : request.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(BodyInserters.fromFormData(form));
        return withBody.exchangeToMono(response -> {
                cookies.store(uri, response.headers().header(HttpHeaders.SET_COOKIE));
                return Mono.justOrEmpty(response.headers().header(HttpHeaders.LOCATION).stream().findFirst())
                    .filter(location -> response.statusCode().is3xxRedirection())
                    .map(uri::resolve)
                    .flatMap(next -> response.releaseBody()
                        .then(Mono.defer(() -> follow(cookies, HttpMethod.GET, next, null, redirects + 1))))
                    .switchIfEmpty(Mono.defer(() -> Mono.just(response)
                        .filter(answer -> !answer.statusCode().isError())
                        .switchIfEmpty(Mono.defer(() -> response.releaseBody().then(Mono.error(new ElicznikException(
                            "The eLicznik portal answered HTTP " + response.statusCode().value() + " to "
                                + uri.getPath())))))
                        .flatMap(answer -> answer.bodyToMono(String.class).defaultIfEmpty(""))
                        .map(body -> new Page(uri, body))));
            })
            .onErrorMap(failure -> !(failure instanceof ElicznikException),
                failure -> new ElicznikException("The eLicznik portal did not answer " + uri.getPath() + ": "
                    + failure.getClass().getSimpleName()));
    }

    private static Optional<URI> formAction(final Page page) {
        final Matcher tags = FORM_TAG.matcher(page.body());
        return tags.results()
            .map(MatchResult::group)
            .filter(tag -> tag.contains(FORM_ID))
            .map(ACTION::matcher)
            .filter(Matcher::find)
            .map(action -> page.uri().resolve(action.group(1).replace("&amp;", "&")))
            .findFirst();
    }

    //a blocked account or a refused login - the login form again, wherever it is answered
    static Optional<ElicznikException> refusal(final Page page) {
        if (BLOCKED_PAGE.equals(page.uri().getPath()) || page.body().contains(TOO_MANY_LOGINS)) {
            return Optional.of(new ElicznikException("The eLicznik portal blocked the account - too many logins"));
        }
        if (page.body().contains(FORM_ID)) {
            return Optional.of(new ElicznikException(
                "The eLicznik portal refused the login - the password expired or the account changed"));
        }
        return Optional.empty();
    }

    private MultiValueMap<String, String> credentials() {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", properties.username());
        form.add("password", properties.password());
        form.add("credentialId", "");
        return form;
    }

    private MultiValueMap<String, String> meter() {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("site[client]", properties.meterId());
        return form;
    }

    private URI service(final String path) {
        return properties.serviceUrl().resolve(path);
    }
}
