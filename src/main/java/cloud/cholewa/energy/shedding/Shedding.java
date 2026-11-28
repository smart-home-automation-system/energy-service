package cloud.cholewa.energy.shedding;

import java.time.LocalDateTime;

//The load shedding now (HAS-523) - the contract of the plugs, the messages, aircon-service and the dashboard of the
//epic HAS-520: active, since when, and endsAt while the grid is back and its stable time runs
public record Shedding(boolean active, LocalDateTime since, LocalDateTime endsAt) {

    static final Shedding OFF = new Shedding(false, null, null);
}
