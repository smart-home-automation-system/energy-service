package cloud.cholewa.energy.api.model;

import java.time.LocalDateTime;

//The body of PUT /home/energy/battery/reserve (HAS-494): enabled - the switch, required; level - 10 - 100 % by 5,
//required; until - a house time after now and at most 7 days ahead, only for a reserve switched on, left out for a
//reserve that holds until it is switched off
public record ReserveRequest(
    Boolean enabled,
    Integer level,
    LocalDateTime until
) {
}
