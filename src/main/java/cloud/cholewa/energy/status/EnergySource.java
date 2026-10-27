package cloud.cholewa.energy.status;

//The sources of the energy of the house the service reads, in the order the dashboard shows them: the grid
//meter (Shelly Pro 3EM, HAS-327), the SOLAX inverter over its local API (HAS-328), the SOFAR inverter
//(HAS-330) and the SolaX cloud (HAS-329). The wire name is the contract of the dashboard
public enum EnergySource {
    METER("meter"),
    SOLAX("solax"),
    SOFAR("sofar"),
    SOLAX_CLOUD("solaxCloud");

    private final String wireName;

    EnergySource(final String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
