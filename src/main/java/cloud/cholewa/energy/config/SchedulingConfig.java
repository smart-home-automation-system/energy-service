package cloud.cholewa.energy.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

//Off under the test profile: a @SpringBootTest context would read the meter and the inverters of the house
//(the lesson of water-service, HAS-307). The tasks come with the readings (HAS-327, HAS-328) - each wrapped
//in ScheduledPass.bounded of cholewa-commons, on the scheduler pool of the library
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
