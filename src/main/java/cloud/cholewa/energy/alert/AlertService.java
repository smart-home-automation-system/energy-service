package cloud.cholewa.energy.alert;

import cloud.cholewa.energy.api.model.AlertReply;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

//The open alarms of the energy of the house (HAS-341): every episode of source_alert that has not ended, newest
//first - the silent sources and the overloaded phases (HAS-332), the alarms of the SOLAX (HAS-329), the faults of the
//SOFAR (HAS-330) and an inverter producing nothing in the sun (HAS-333). One query; the dashboard words the kinds
@Service
@RequiredArgsConstructor
public class AlertService {

    private static final String SOLAX = "solax";
    private static final String ALARM = "alarm";

    private final SourceAlertRepository sourceAlertRepository;

    public Mono<List<AlertReply>> alerts() {
        return sourceAlertRepository.findByClosedAtIsNullOrderByOpenedAtDesc()
            .map(AlertService::reply)
            .collectList();
    }

    //the detail of a SOLAX alarm is "<error code>|<start time>|<name>" (V6): the dashboard gets the name; the fault of
    //the SOFAR is its name already; the other kinds have none
    static AlertReply reply(final SourceAlertEntity row) {
        final String detail = row.detail() != null && SOLAX.equals(row.source()) && ALARM.equals(row.kind())
            ? nameOf(row.detail())
            : row.detail();
        return new AlertReply(row.source(), row.kind(), row.openedAt(), detail);
    }

    //the name, the third part; a detail of another shape is answered whole
    static String nameOf(final String detail) {
        final String[] parts = detail.split("\\|", 3);
        return parts.length == 3 ? parts[2] : detail;
    }
}
