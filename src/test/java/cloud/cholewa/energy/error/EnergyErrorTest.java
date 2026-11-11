package cloud.cholewa.energy.error;

import cloud.cholewa.commons.error.model.ErrorId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

//the names are the codes of the answers - wire contract once the dashboard branches on them (HAS-328)
class EnergyErrorTest {

    @Test
    void should_keep_the_codes_of_the_refused_requests() {
        assertThat(Arrays.stream(EnergyError.values()).map(ErrorId::codeOf))
            .containsExactly("NOT_FOUND_INVERTER", "REGISTRY_UNAVAILABLE", "INVALID_RANGE", "UNKNOWN_METRIC",
                "INVALID_CHARGE", "CHARGE_IN_PROGRESS", "TARGET_REACHED", "NOT_FOUND_CHARGE", "WRITE_TOO_SOON",
                "INVERTER_UNAVAILABLE", "DEVICE_FAILED", "INVALID_AUTOMATION", "NOT_FOUND_PLANNED_OUTAGE",
                "FETCH_TOO_SOON", "ELICZNIK_UNAVAILABLE");
    }
}
