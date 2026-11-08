package com.movie.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReservationWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReservationWorkerApplication.class, args);
    }
}
