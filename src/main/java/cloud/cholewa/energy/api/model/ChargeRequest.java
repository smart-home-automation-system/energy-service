package cloud.cholewa.energy.api.model;

import java.time.LocalDateTime;

//The body of POST /home/energy/battery/charge (HAS-336), JSON - {} for the defaults; both fields optional: targetSoc -
//the SOC to charge to, 10 - 100, 100 when left out; until - the house time the charge ends at, at most 23 h ahead - the
//estimate when left out
public record ChargeRequest(Integer targetSoc, LocalDateTime until) {
}
