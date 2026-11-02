package cloud.cholewa.energy.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

//The client of weather-service (the illuminance of the station, HAS-333). Built from the injected builder - traced and
//logged by Logbook - with the timeouts the task asks for (5 s) and the pool limits of the client of database-service
@Configuration
public class WeatherServiceClientConfig {

    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    static final int CONNECT_TIMEOUT_MILLIS = 5000;

    @Bean(destroyMethod = "dispose")
    ConnectionProvider weatherServiceConnectionProvider() {
        return ConnectionProvider.builder("weather-service")
            .maxIdleTime(DatabaseServiceClientConfig.MAX_IDLE_TIME)
            .maxLifeTime(DatabaseServiceClientConfig.MAX_LIFE_TIME)
            .build();
    }

    @Bean
    WebClient weatherServiceWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider weatherServiceConnectionProvider,
        final InternalServicesProperties services
    ) {
        final HttpClient httpClient = HttpClient.create(weatherServiceConnectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
            .responseTimeout(RESPONSE_TIMEOUT);

        return webClientBuilder.clone()
            .baseUrl(services.weather().toString())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
