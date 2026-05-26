package com.movie.waiting;

import com.movie.waiting.config.QueueAdmissionProperties;
import com.movie.waiting.config.QueueRegistrationProperties;
import com.movie.waiting.config.QueueStatusProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        QueueRegistrationProperties.class,
        QueueStatusProperties.class,
        QueueAdmissionProperties.class
})
public class WaitingApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaitingApiApplication.class, args);
    }
}
