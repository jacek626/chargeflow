package pl.chargeflow.session;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ChargingSessionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChargingSessionServiceApplication.class, args);
    }
}
