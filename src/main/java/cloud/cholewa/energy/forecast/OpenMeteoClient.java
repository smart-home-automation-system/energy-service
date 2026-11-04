package cloud.cholewa.energy.forecast;

import cloud.cholewa.energy.production.LocationProperties;
import io.netty.channel.ChannelOption;
import org.springframework.http.HttpStatusCode;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

//The hourly forecast of Open-Meteo for one plane of panels (HAS-371), checked against the API on 2026-11-03: GET
///v1/forecast with the coordinates of the house, the tilt and azimuth of the plane (0 south), seven days. No key, no
//account (non-commercial use). What is easy to break:
//- the radiation of an hour is the mean of the hour BEFORE its time ("averaged over the past hour"): the value at 11:00
//  is 10:00-11:00, so it is stored at 10:00, the start of the hour it covers
//- the ISO times of an answer keep the offset of the moment of the request across a change of the clocks, so the times
//  are asked as unix seconds (timeformat=unixtime) and turned into the house time here; timezone only aligns the days
//- an invalid request is HTTP 400 with {"reason", "error": true}
//- the model answers a radiation slightly below 0 at night now and then (-1 W/m2 of shortwave_radiation, 2026-11-03):
//  there is no negative radiation, so both radiation series are read as 0 then; the temperature is taken as it comes
//A failure is an OpenMeteoException with a fixed message - never the body. A new connection for every call, 5 s to
//connect and 10 s for an answer; nothing retried - the next fetch comes with the next interval. Built from the injected
//builder: traced and logged by Logbook
@Component
public class OpenMeteoClient {

    static final int CONNECT_TIMEOUT_MILLIS = 5000;
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    static final int FORECAST_DAYS = 7;
    //seven days of five series is about 10 kB
    static final int MAX_ANSWER_BYTES = 1024 * 1024;
    static final String IRRADIANCE = "global_tilted_irradiance";
    static final String HORIZONTAL = "shortwave_radiation";
    static final String CLOUD_COVER = "cloud_cover";
    static final String TEMPERATURE = "temperature_2m";
    static final String SNOWFALL = "snowfall";
    private static final Duration HOUR = Duration.ofHours(1);

    private final WebClient webClient;
    private final JsonMapper jsonMapper;
    private final LocationProperties location;
    private final ZoneId house;

    public OpenMeteoClient(
        final WebClient.Builder webClientBuilder,
        final JsonMapper jsonMapper,
        final ForecastProperties properties,
        final LocationProperties location,
        final Clock clock
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
        this.location = location;
        this.house = clock.getZone();
    }

    //the hours of the plane in the order of the answer
    public Mono<List<HourForecast>> forecast(final ForecastProperties.Plane plane) {
        return webClient.get()
            .uri(uri -> uri.path("/v1/forecast")
                .queryParam("latitude", location.latitude())
                .queryParam("longitude", location.longitude())
                .queryParam("hourly", String.join(",", IRRADIANCE, HORIZONTAL, CLOUD_COVER, TEMPERATURE, SNOWFALL))
                .queryParam("tilt", plane.tilt())
                .queryParam("azimuth", plane.azimuth())
                .queryParam("forecast_days", FORECAST_DAYS)
                .queryParam("timeformat", "unixtime")
                .queryParam("timezone", house.getId())
                .build())
            .retrieve()
            .onStatus(HttpStatusCode::isError, response ->
                Mono.just(new OpenMeteoException("Open-Meteo answered HTTP " + response.statusCode().value())))
            .bodyToMono(String.class)
            .switchIfEmpty(Mono.error(() -> new OpenMeteoException("Open-Meteo answered without a body")))
            .map(this::hours)
            .onErrorMap(failure -> !(failure instanceof OpenMeteoException),
                failure -> new OpenMeteoException("Open-Meteo did not answer: " + failure.getClass().getSimpleName()));
    }

    private List<HourForecast> hours(final String body) {
        final JsonNode hourly = tree(body).path("hourly");
        final JsonNode time = hourly.path("time");
        if (!time.isArray() || time.isEmpty()) {
            throw new OpenMeteoException("Open-Meteo answered no hours");
        }
        final List<JsonNode> series = List.of(hourly.path(IRRADIANCE), hourly.path(HORIZONTAL), hourly.path(CLOUD_COVER),
            hourly.path(TEMPERATURE), hourly.path(SNOWFALL));
        if (series.stream().anyMatch(values -> !values.isArray() || values.size() != time.size())) {
            throw new OpenMeteoException("Open-Meteo answered series that are not the hours asked");
        }
        final List<HourForecast> hours = new ArrayList<>(time.size());
        for (int i = 0; i < time.size(); i++) {
            hours.add(new HourForecast(start(time.get(i)), radiation(series.get(0).get(i)), radiation(series.get(1).get(i)),
                value(series.get(2).get(i)), value(series.get(3).get(i)), value(series.get(4).get(i))));
        }
        return hours;
    }

    //the house time the hour of the value starts at: its time less an hour
    private LocalDateTime start(final JsonNode time) {
        if (!time.isIntegralNumber()) {
            throw new OpenMeteoException("Open-Meteo answered a time that is not unix seconds");
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(time.asLong()).minus(HOUR), house);
    }

    private static Double radiation(final JsonNode value) {
        final Double radiation = value(value);
        return radiation != null && radiation < 0 ? Double.valueOf(0) : radiation;
    }

    private static Double value(final JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw new OpenMeteoException("Open-Meteo answered a value that is not a number");
        }
        return value.asDouble();
    }

    private JsonNode tree(final String body) {
        try {
            return jsonMapper.readTree(body);
        } catch (final RuntimeException notJson) {
            throw new OpenMeteoException("Open-Meteo answered something that is not JSON");
        }
    }
}
