package cloud.cholewa.energy.error;

import cloud.cholewa.commons.error.model.ErrorId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

//The causes of a request the API refuses (HAS-328). Their names are the codes of the answer (ErrorId.codeOf) - wire
//contract for a caller that branches on them, the dashboard first of all, pinned by EnergyErrorTest
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum EnergyError implements ErrorId {

    //no inverter of the registry has this id (solax, sofar)
    NOT_FOUND_INVERTER("Inverter not found"),

    //the inverters of the registry are not known yet - database-service did not answer since the start
    REGISTRY_UNAVAILABLE("Device registry unavailable"),

    //a range of the history that is empty, reversed or too long (HAS-334: the outages, at most 366 days)
    INVALID_RANGE("Invalid range"),

    //a metric the history does not have (HAS-331): the details list the names there are, never the one asked for
    UNKNOWN_METRIC("Unknown metric"),

    //a charge of the battery asked with a target or an end outside the bounds, or by an unknown source (HAS-336)
    INVALID_CHARGE("Invalid charge"),

    //a charge of the battery asked while one runs or is being ended (HAS-336)
    CHARGE_IN_PROGRESS("Charge in progress"),

    //a charge asked for a battery already at its target (HAS-336)
    TARGET_REACHED("Target reached"),

    //no charge to cancel (HAS-336)
    NOT_FOUND_CHARGE("Charge not found"),

    //a write to the inverter within a minute of the last one (HAS-336: at most one a minute)
    WRITE_TOO_SOON("Write too soon"),

    //the inverter offline for the SolaX cloud, or its state of charge not known (HAS-336)
    INVERTER_UNAVAILABLE("Inverter unavailable"),

    //the SolaX cloud refused the command, or the inverter did not carry it out (HAS-336)
    DEVICE_FAILED("Device failed"),

    //settings of the automatic charge outside their bounds, or missing (HAS-338)
    INVALID_AUTOMATION("Invalid automation"),

    //no planned outage still announced has this id (HAS-338)
    NOT_FOUND_PLANNED_OUTAGE("Planned outage not found");

    private final String description;
}
