package cloud.cholewa.energy.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//The settings of the SOLAX as read from its dongle (HAS-518), in GET /home/energy/battery/reserve and /status - the
//contract of the reserve card of the energy page (HAS-519): workMode - self use, back up, feed-in priority, or other for
//a mode the service does not know; minSoc - the Min SoC of the Back Up mode, the floor the inverter holds the battery
//at while it runs Back Up, %; chargeTo - Charge Battery To of Back Up, %; a percentage not known left out; readAt - when
//the dongle answered them. The whole object is left out while the settings are not known (not read yet, or read longer
//ago than energy.solax.settings.stale-after)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InverterSettingsReply(
    String workMode,
    Integer minSoc,
    Integer chargeTo,
    LocalDateTime readAt
) {
}
