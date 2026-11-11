package cloud.cholewa.energy.elicznik;

//A failure of the eLicznik portal (HAS-340): its message names what failed, never an answer of the portal, the account
//or the password
public class ElicznikException extends RuntimeException {

    public ElicznikException(final String message) {
        super(message);
    }
}
