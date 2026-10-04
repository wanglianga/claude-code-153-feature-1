package com.waste;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class WasteApplication {
    public static void main(String[] args) {
        SpringApplication.run(WasteApplication.class, args);
    }
}
