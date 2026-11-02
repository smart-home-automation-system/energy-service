package cloud.cholewa.energy.production;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

//The illuminance of the weather station of the house, from GET /home/weather/current of weather-service (HAS-333): the
//metric illuminance - {value (lx), measuredAt (house time), stale}. The station has no solar radiation (W/m2) - the
//illuminance is the one metric of the sun (checked 2026-11-02). An answer without the metric, or with a value that is
//not a number, is no illuminance (empty); an error of weather-service is a failed call, never a dark sky
@Component
public class WeatherClient {

    private final WebClient weatherServiceWebClient;

    public WeatherClient(@Qualifier("weatherServiceWebClient") final WebClient weatherServiceWebClient) {
        this.weatherServiceWebClient = weatherServiceWebClient;
    }

    //lux, the house time of the reading and whether weather-service holds it for stale
    public record Illuminance(double lux, LocalDateTime measuredAt, boolean stale) {
    }

    public Mono<Illuminance> illuminance() {
        return weatherServiceWebClient.get()
            .uri("/home/weather/current")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response ->
                Mono.just(new WeatherCallException("weather-service answered " + response.statusCode().value())))
            .bodyToMono(JsonNode.class)
            .switchIfEmpty(Mono.error(() -> new WeatherCallException("weather-service answered without a body")))
            .flatMap(current -> Mono.justOrEmpty(of(current.path("illuminance"))));
    }

    static Optional<Illuminance> of(final JsonNode metric) {
        return Optional.of(metric)
            .filter(node -> node.path("value").isNumber())
            .map(node -> new Illuminance(node.path("value").asDouble(), time(node.path("measuredAt").asString(null)),
                node.path("stale").asBoolean(true)));
    }

    private static LocalDateTime time(final String value) {
        try {
            return Optional.ofNullable(value).map(LocalDateTime::parse).orElse(null);
        } catch (final DateTimeParseException notTime) {
            return null;
        }
    }

    //a call of weather-service that failed - the message is the status alone
    public static class WeatherCallException extends RuntimeException {

        public WeatherCallException(final String message) {
            super(message);
        }
    }
}
