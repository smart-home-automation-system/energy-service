package cloud.cholewa.energy.api;

import cloud.cholewa.energy.api.model.AutomationReply;
import cloud.cholewa.energy.api.model.AutomationRequest;
import cloud.cholewa.energy.api.model.SheddingRequest;
import cloud.cholewa.energy.automation.AutomationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

//The settings of the automation of the energy page under /home/energy/automation, apart from EnergyController since
//HAS-523 (its dependencies over the limit of Sonar): the automatic charge before a planned outage (HAS-338) and the switch
//of the load shedding during an outage of the grid (HAS-523)
@RestController
@RequiredArgsConstructor
public class AutomationController {

    private final AutomationService automationService;

    //the settings of the automatic charge before a planned outage, the switch first (HAS-338)
    @GetMapping("automation")
    Mono<AutomationReply> automation() {
        return automationService.settings();
    }

    @PutMapping("automation")
    Mono<AutomationReply> replaceAutomation(@RequestBody final AutomationRequest request) {
        return automationService.replace(request);
    }

    //HAS-523: the switch of the load shedding during an outage of the grid, apart from the settings of the charge
    @PutMapping("automation/shedding")
    Mono<AutomationReply> replaceShedding(@RequestBody final SheddingRequest request) {
        return automationService.replaceShedding(request);
    }
}
