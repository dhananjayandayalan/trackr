package dev.trakr.api;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.SpringApplication;
import org.springframework.modulith.Modulithic;

@SpringBootApplication
@ConfigurationPropertiesScan
// shared holds beans every module needs (Clock, PasswordEncoder, security, error handling); Modulith can't
// see that through Spring's own types, so @ApplicationModuleTest always starts it alongside the module
@Modulithic(sharedModules = "shared")
public class ApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
