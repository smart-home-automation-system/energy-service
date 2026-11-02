package cloud.cholewa.energy.alert;

import cloud.cholewa.energy.api.model.AlertReply;
import cloud.cholewa.energy.database.SourceAlertEntity;
import cloud.cholewa.energy.database.SourceAlertRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 2, 12, 0);

    @Mock
    private SourceAlertRepository repository;

    @InjectMocks
    private AlertService sut;

    //HAS-341: a SOLAX alarm gives its name alone, a SOFAR fault is its name already, the other kinds have no detail
    @Test
    void should_answer_the_open_alarms_with_the_names_of_the_alarms() {
        when(repository.findByClosedAtIsNullOrderByOpenedAtDesc()).thenReturn(Flux.just(
            new SourceAlertEntity(1L, "solax", "alarm", AT, AT, null, "1|2026-11-02 11:58:00|Grid Volt Fault"),
            new SourceAlertEntity(2L, "sofar", "alarm", AT, AT, null, "GridOVP"),
            new SourceAlertEntity(3L, "phase b", "overload", AT, AT, null, null),
            new SourceAlertEntity(4L, "solax", "production", AT, null, null, null)));

        sut.alerts()
            .as(StepVerifier::create)
            .expectNext(List.of(
                new AlertReply("solax", "alarm", AT, "Grid Volt Fault"),
                new AlertReply("sofar", "alarm", AT, "GridOVP"),
                new AlertReply("phase b", "overload", AT, null),
                new AlertReply("solax", "production", AT, null)))
            .verifyComplete();
    }

    @Test
    void should_answer_none_as_an_empty_list() {
        when(repository.findByClosedAtIsNullOrderByOpenedAtDesc()).thenReturn(Flux.empty());

        sut.alerts().as(StepVerifier::create).expectNext(List.of()).verifyComplete();
    }

    //a detail the SOLAX watch did not write in three parts is answered whole, never cut
    @Test
    void should_answer_a_detail_of_another_shape_whole() {
        assertThat(AlertService.nameOf("17")).isEqualTo("17");
        assertThat(AlertService.nameOf("1|t|Name|with|bars"))
            .isEqualTo("Name|with|bars");
    }
}
