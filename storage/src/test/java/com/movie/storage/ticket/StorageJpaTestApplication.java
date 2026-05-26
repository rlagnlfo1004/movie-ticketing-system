package com.movie.storage.ticket;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootConfiguration
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = "com.movie.storage")
@EnableJpaRepositories("com.movie.storage")
class StorageJpaTestApplication {
}
