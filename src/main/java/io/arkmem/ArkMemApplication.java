package io.arkmem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ArkMemApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArkMemApplication.class, args);
    }
}
