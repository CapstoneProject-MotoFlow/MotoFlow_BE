package fu.hl.g64.motorflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MotorFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(MotorFlowApplication.class, args);
    }

}
