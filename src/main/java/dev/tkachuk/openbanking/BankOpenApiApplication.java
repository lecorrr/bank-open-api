package dev.tkachuk.openbanking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BankOpenApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankOpenApiApplication.class, args);
    }
}
