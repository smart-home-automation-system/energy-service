package cloud.cholewa.energy.solaxcloud;

//Feeds CloudDays from a test of another package, as SolaxCloudReader does (HAS-331)
public final class CloudDaysFeeder {

    private CloudDaysFeeder() {
    }

    public static void feed(final CloudDays days, final CloudInverter inverter, final CloudPlant plant) {
        days.inverter(inverter);
        days.plant(plant);
    }
}
