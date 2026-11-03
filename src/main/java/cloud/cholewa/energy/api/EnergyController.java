package cloud.cholewa.energy.api;

import cloud.cholewa.energy.alert.AlertService;
import cloud.cholewa.energy.api.model.AlertReply;
import cloud.cholewa.energy.api.model.BatteryReply;
import cloud.cholewa.energy.api.model.ChargeReply;
import cloud.cholewa.energy.api.model.ChargeRequest;
import cloud.cholewa.energy.api.model.ChargeRunReply;
import cloud.cholewa.energy.api.model.DayReply;
import cloud.cholewa.energy.api.model.FlowsReply;
import cloud.cholewa.energy.api.model.GridReply;
import cloud.cholewa.energy.api.model.HistoryReply;
import cloud.cholewa.energy.api.model.InverterReply;
import cloud.cholewa.energy.api.model.MonthsReply;
import cloud.cholewa.energy.api.model.OutageReply;
import cloud.cholewa.energy.api.model.PlannedOutageReply;
import cloud.cholewa.energy.api.model.StatusReply;
import cloud.cholewa.energy.charge.ChargeService;
import cloud.cholewa.energy.days.DaysService;
import cloud.cholewa.energy.flows.FlowsService;
import cloud.cholewa.energy.history.HistoryService;
import cloud.cholewa.energy.inverter.BatteryService;
import cloud.cholewa.energy.inverter.InverterService;
import cloud.cholewa.energy.meter.GridService;
import cloud.cholewa.energy.outage.GridOutageService;
import cloud.cholewa.energy.status.EnergyStatusService;
import cloud.cholewa.energy.tauron.PlannedOutageService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final GridOutageService gridOutageService;
    private final FlowsService flowsService;
    private final HistoryService historyService;
    private final DaysService daysService;
    private final AlertService alertService;
    private final PlannedOutageService plannedOutageService;
    private final ChargeService chargeService;

    //every source of energy with whether it answers - the contract of the availability card of the dashboard - and
    //the grid as the SOLAX sees it (HAS-334)
    @GetMapping("status")
    Mono<StatusReply> status() {
        return energyStatusService.reply();
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

    //the outages of the grid that started in [from, to), house times, at most 366 days (HAS-334)
    @GetMapping("outages")
    Mono<List<OutageReply>> outages(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime to
    ) {
        return gridOutageService.outages(from, to);
    }

    //the outages Tauron announces for the house that have not ended, the soonest first (HAS-337)
    @GetMapping("outages/planned")
    Mono<List<PlannedOutageReply>> plannedOutages() {
        return plannedOutageService.planned();
    }

    //the alarms that last, newest first - the open alarms of the dashboard (HAS-341)
    @GetMapping("alerts")
    Mono<List<AlertReply>> alerts() {
        return alertService.alerts();
    }

    //the battery of the SOLAX now (HAS-328)
    @GetMapping("battery")
    Mono<BatteryReply> battery() {
        return batteryService.battery();
    }

    //a charge of the battery from the grid (HAS-336): the run as requested - its command is followed by the watch
    @PostMapping("battery/charge")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<ChargeRunReply> charge(
        @RequestBody final ChargeRequest request,
        @RequestParam(defaultValue = "dashboard") final String source
    ) {
        return chargeService.start(request, source);
    }

    //the charge going on, ended and the baseline of Self Use set back (HAS-336)
    @DeleteMapping("battery/charge")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<ChargeRunReply> cancelCharge() {
        return chargeService.cancel();
    }

    //the charge going on or being ended, with the battery now (HAS-336)
    @GetMapping("battery/charge")
    Mono<ChargeReply> currentCharge() {
        return chargeService.current();
    }

    //the charges requested in [from, to), house times, at most 366 days (HAS-336)
    @GetMapping("battery/charge/runs")
    Mono<List<ChargeRunReply>> chargeRuns(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime to
    ) {
        return chargeService.runs(from, to);
    }

    //the balance of the house now and the day so far (HAS-331)
    @GetMapping("flows")
    Mono<FlowsReply> flows() {
        return flowsService.flows();
    }

    //one metric in [from, to), house times, at most 31 days, in buckets (HAS-331)
    @GetMapping("history")
    Mono<HistoryReply> history(
        @RequestParam final String metric,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime to
    ) {
        return historyService.history(metric, from, to);
    }

    //the closed days of [from, to], both included, at most 366 days (HAS-331)
    @GetMapping("days")
    Mono<List<DayReply>> days(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to
    ) {
        return daysService.days(from, to);
    }

    //the billing year starting in energy.settlement.period-start of "year", with its net-metering in kWh (HAS-331)
    @GetMapping("months")
    Mono<MonthsReply> months(@RequestParam final int year) {
        return daysService.months(year);
    }
}
