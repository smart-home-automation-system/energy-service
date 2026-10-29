package cloud.cholewa.energy.solax;

//the dongle refused the registration number of the registry - it answers 0:code,message:"failed"} to a wrong one.
//The message never carries the number
public class RegistrationRefusedException extends SolaxCallException {

    public RegistrationRefusedException() {
        super("The dongle refused the registration number of the registry");
    }
}
