package cloud.cholewa.energy.solax;

//a read of the dongle of the SOLAX that did not end with a reading of type 33: no answer, no body, not JSON, another
//type, a Data too short. Never answered - the reader logs it and keeps the last reading
public class SolaxCallException extends RuntimeException {

    public SolaxCallException(final String message) {
        super(message);
    }
}
