package cloud.cholewa.energy.charge;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

//The battery reserve followed every minute (HAS-494), bounded at 50 s, in the lane of InverterCommands: the end of a
//reserve set until a time, the command sent followed, a write due sent (ReserveService.pass)
@Slf4j
@Component
@RequiredArgsConstructor
public class ReserveWatch {

    static final Duration LIMIT = Duration.ofSeconds(50);

    private final ReserveService reserveService;
    private final InverterCommands commands;

    @Scheduled(initialDelayString = "PT50S", fixedDelayString = "PT1M")
    Mono<Void> watch() {
        return ScheduledPass.bounded("Battery reserve watch", LIMIT, () -> commands.serially(Mono.defer(this::pass)));
    }

    Mono<Void> pass() {
        return reserveService.pass()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The battery reserve could not be followed, tried again with the next pass: {}", failure.toString())));
    }
}
