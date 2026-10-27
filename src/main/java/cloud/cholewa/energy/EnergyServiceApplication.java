package cloud.cholewa.energy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EnergyServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnergyServiceApplication.class, args);
    }

}
