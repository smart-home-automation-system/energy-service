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
    REGISTRY_UNAVAILABLE("Device registry unavailable");

    private final String description;
}
