package cloud.cholewa.energy.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

//A request the API refuses (HAS-328): the status says whose problem it is, the error names the cause, the message is
//answered as details
@Getter
public class EnergyException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final EnergyError error;

    public EnergyException(final HttpStatus httpStatus, final EnergyError error, final String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.error = error;
    }

    public static EnergyException inverterNotFound(final String id) {
        return new EnergyException(HttpStatus.NOT_FOUND, EnergyError.NOT_FOUND_INVERTER,
            "No inverter " + id + " is registered");
    }

    public static EnergyException registryUnavailable() {
        return new EnergyException(HttpStatus.SERVICE_UNAVAILABLE, EnergyError.REGISTRY_UNAVAILABLE,
            "The inverters of the device registry are not known yet");
    }
}
