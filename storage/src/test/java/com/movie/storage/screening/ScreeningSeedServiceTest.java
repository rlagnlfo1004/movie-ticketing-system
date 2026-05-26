package com.movie.storage.screening;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest
@ContextConfiguration(classes = ScreeningSeedServiceTest.TestApplication.class)
@Import(ScreeningSeedService.class)
class ScreeningSeedServiceTest {

    @Autowired
    ScreeningSeedService service;

    @Autowired
    ScreeningRepository repository;

    @Test
    void createsAndReusesDefaultScreening() {
        Instant startsAt = Instant.parse("2026-05-12T10:00:00Z");

        Screening created = service.createOrReuse("1", "Demo Movie", startsAt, 100);
        Screening reused = service.createOrReuse("1", "Changed Movie", startsAt.plusSeconds(60), 200);

        assertThat(created.getId()).isEqualTo("1");
        assertThat(reused.getMovieTitle()).isEqualTo("Demo Movie");
        assertThat(repository.count()).isEqualTo(1);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @AutoConfigurationPackage(basePackages = "com.movie.storage")
    @EnableJpaRepositories("com.movie.storage")
    static class TestApplication {
    }
}
