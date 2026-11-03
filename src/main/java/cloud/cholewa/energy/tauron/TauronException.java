package cloud.cholewa.energy.tauron;

//A read of Tauron's announcements that failed (HAS-337) - the message is fixed by the client: never the body of an answer
public class TauronException extends RuntimeException {

    public TauronException(final String message) {
        super(message);
    }
}
