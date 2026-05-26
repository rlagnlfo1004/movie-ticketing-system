package com.movie.reservation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movie.storage.StorageModule;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableConfigurationProperties(ReservationProperties.class)
@AutoConfigurationPackage(basePackageClasses = StorageModule.class)
@EnableJpaRepositories(basePackages = "com.movie.storage")
public class ReservationConfig {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
