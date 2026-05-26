package com.movie.reservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.movie")
public class ReservationApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReservationApiApplication.class, args);
    }
}
