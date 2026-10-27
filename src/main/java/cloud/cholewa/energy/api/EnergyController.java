package cloud.cholewa.energy.api;

import cloud.cholewa.energy.api.model.SourceStatusReply;
import cloud.cholewa.energy.status.EnergyStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

//The API of the energy of the house, under the base path /home/energy; routed by the gateway by path, every
//method (HAS-325). Takes the request and hands it to the services
@RestController
@RequiredArgsConstructor
public class EnergyController {

    private final EnergyStatusService energyStatusService;

    //every source of energy with whether it answers - the contract of the availability card of the dashboard
    @GetMapping("status")
    Mono<List<SourceStatusReply>> status() {
        return energyStatusService.status();
    }
}
