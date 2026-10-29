package cloud.cholewa.energy.solax;

import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

//The WARNs of a read repeated every few seconds (HAS-328): the first failure of a kind is a WARN, the same kind again
//at most once an hour while it lasts, another kind at once; the first success after failures is an INFO. So a dongle
//that is off, or a wrong registration number, is one WARN an hour, not one every read
final class FailureLog {

    static final Duration REPEAT_AFTER = Duration.ofHours(1);

    private final Logger log;
    private final Clock clock;
    private final AtomicReference<Said> said = new AtomicReference<>();

    FailureLog(final Logger log, final Clock clock) {
        this.log = log;
        this.clock = clock;
    }

    private record Said(String kind, Instant at) {
    }

    void failed(final String what, final Throwable failure) {
        final Instant now = clock.instant();
        final String kind = kindOf(failure);
        final Said before = said.get();
        if (before == null || !before.kind().equals(kind) || !now.isBefore(before.at().plus(REPEAT_AFTER))) {
            said.set(new Said(kind, now));
            log.warn("The {} could not be read: {}", what, failure.getMessage());
        }
    }

    //the class and the cause before its details: "The dongle did not answer: <the exception>" is one kind whatever
    //the exception says, "The dongle answered 500" another
    static String kindOf(final Throwable failure) {
        final String message = String.valueOf(failure.getMessage());
        final int details = message.indexOf(": ");
        return failure.getClass().getName() + " " + (details < 0 ? message : message.substring(0, details));
    }

    void succeeded(final String what) {
        if (said.getAndSet(null) != null) {
            log.info("The {} is read again", what);
        }
    }
}
