package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//One open alarm of GET /home/energy/alerts (HAS-341) - the contract of the dashboard: an episode of source_alert that
//has not ended. source - solax, sofar, meter, phase a / b / c; kind - silent, overload, alarm, production;
//openedAt - the house time it is so since; detail - the name of an alarm as the inverter words it (the SOLAX alarm
//without its code and start, the fault of the SOFAR as the Solarman cloud answers it), left out for the other kinds
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlertReply(
    String source,
    String kind,
    LocalDateTime openedAt,
    String detail
) {
}
