package cloud.cholewa.energy.api;

import cloud.cholewa.energy.api.model.BatteryReply;
import cloud.cholewa.energy.api.model.GridReply;
import cloud.cholewa.energy.api.model.InverterReply;
import cloud.cholewa.energy.api.model.SourceStatusReply;
import cloud.cholewa.energy.inverter.BatteryService;
import cloud.cholewa.energy.inverter.InverterService;
import cloud.cholewa.energy.meter.GridService;
import cloud.cholewa.energy.status.EnergyStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

//The API of the energy of the house, under the base path /home/energy; routed by the gateway by path, every
//method (HAS-325). Takes the request and hands it to the services
@RestController
@RequiredArgsConstructor
public class EnergyController {

    private final EnergyStatusService energyStatusService;
    private final GridService gridService;
    private final InverterService inverterService;
    private final BatteryService batteryService;

    //every source of energy with whether it answers - the contract of the availability card of the dashboard
    @GetMapping("status")
    Mono<List<SourceStatusReply>> status() {
        return energyStatusService.status();
    }

    //the grid meter now (HAS-327)
    @GetMapping("grid")
    Mono<GridReply> grid() {
        return gridService.grid();
    }

    //every inverter of the registry now (HAS-328)
    @GetMapping("inverters")
    Mono<List<InverterReply>> inverters() {
        return inverterService.inverters();
    }

    //one inverter by its brand - solax or sofar (HAS-328)
    @GetMapping("inverters/{id}")
    Mono<InverterReply> inverter(@PathVariable final String id) {
        return inverterService.inverter(id);
    }

    //the battery of the SOLAX now (HAS-328)
    @GetMapping("battery")
    Mono<BatteryReply> battery() {
        return batteryService.battery();
    }
}
