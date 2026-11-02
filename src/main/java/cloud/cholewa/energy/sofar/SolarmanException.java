package cloud.cholewa.energy.sofar;

//A call of the Solarman cloud that failed (HAS-330) - the message is fixed by the client: never the body of an answer
public class SolarmanException extends RuntimeException {

    public SolarmanException(final String message) {
        super(message);
    }
}
