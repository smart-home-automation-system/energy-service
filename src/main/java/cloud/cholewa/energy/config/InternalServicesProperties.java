package cloud.cholewa.energy.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

//The services this one calls inside the cluster, by k8s DNS (HAS-328). database - database-service, the device
//registry the inverters (the kind inverter, HAS-326) are read from
@Validated
@ConfigurationProperties("internal.service")
public record InternalServicesProperties(@NotNull URI database) {
}
