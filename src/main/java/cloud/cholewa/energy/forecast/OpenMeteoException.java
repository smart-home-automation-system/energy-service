package cloud.cholewa.energy.forecast;

//A fetch of the Open-Meteo forecast that failed (HAS-371) - the message is fixed by the client: never the body of an answer
public class OpenMeteoException extends RuntimeException {

    public OpenMeteoException(final String message) {
        super(message);
    }
}
