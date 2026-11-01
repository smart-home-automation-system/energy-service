package cloud.cholewa.energy.solaxcloud;

//A call of the SolaX cloud that failed (HAS-329) - the message is fixed by the client: never the body of an answer,
//which may quote the token back
public class SolaxCloudException extends RuntimeException {

    public SolaxCloudException(final String message) {
        super(message);
    }
}
