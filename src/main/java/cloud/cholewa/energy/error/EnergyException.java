package cloud.cholewa.energy.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.List;

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

    public static EnergyException invalidRange(final String message) {
        return new EnergyException(HttpStatus.BAD_REQUEST, EnergyError.INVALID_RANGE, message);
    }

    public static EnergyException unknownMetric(final List<String> known) {
        return new EnergyException(HttpStatus.BAD_REQUEST, EnergyError.UNKNOWN_METRIC,
            "The metric is one of " + String.join(", ", known));
    }

    public static EnergyException invalidCharge(final String message) {
        return new EnergyException(HttpStatus.BAD_REQUEST, EnergyError.INVALID_CHARGE, message);
    }

    public static EnergyException chargeInProgress() {
        return new EnergyException(HttpStatus.CONFLICT, EnergyError.CHARGE_IN_PROGRESS,
            "A charge of the battery runs or is being ended");
    }

    public static EnergyException targetReached(final double soc, final int target) {
        return new EnergyException(HttpStatus.CONFLICT, EnergyError.TARGET_REACHED,
            "The battery is at " + Math.round(soc) + " %, the target is " + target + " %");
    }

    public static EnergyException chargeNotFound() {
        return new EnergyException(HttpStatus.NOT_FOUND, EnergyError.NOT_FOUND_CHARGE, "No charge of the battery runs");
    }

    public static EnergyException writeTooSoon() {
        return new EnergyException(HttpStatus.TOO_MANY_REQUESTS, EnergyError.WRITE_TOO_SOON,
            "The inverter takes at most one command a minute");
    }

    public static EnergyException inverterUnavailable(final String message) {
        return new EnergyException(HttpStatus.SERVICE_UNAVAILABLE, EnergyError.INVERTER_UNAVAILABLE, message);
    }

    public static EnergyException deviceFailed(final String message) {
        return new EnergyException(HttpStatus.BAD_GATEWAY, EnergyError.DEVICE_FAILED, message);
    }

    public static EnergyException registryUnavailable() {
        return new EnergyException(HttpStatus.SERVICE_UNAVAILABLE, EnergyError.REGISTRY_UNAVAILABLE,
            "The inverters of the device registry are not known yet");
    }
}
