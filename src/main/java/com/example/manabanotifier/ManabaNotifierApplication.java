package com.example.manabanotifier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ManabaNotifierApplication {

    public static void main(String[] args) {
        SpringApplication.run(ManabaNotifierApplication.class, args);
    }
}
