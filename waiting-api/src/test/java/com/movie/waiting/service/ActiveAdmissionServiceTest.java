package com.movie.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.movie.waiting.config.QueueRegistrationProperties;
import com.movie.waiting.dto.ActiveAdmissionResponse.Status;
import com.movie.waiting.exception.InvalidQueueTokenException;
import com.movie.waiting.exception.InvalidScreeningException;
import com.movie.waiting.repository.InMemoryActiveAdmissionStore;
import com.movie.waiting.repository.RedisActiveAdmissionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActiveAdmissionServiceTest {

    private RedisActiveAdmissionRepository repository;
    private ActiveAdmissionService service;

    @BeforeEach
    void setUp() {
        repository = new RedisActiveAdmissionRepository(
                "waiting:screening",
                new InMemoryActiveAdmissionStore(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        );
        service = new ActiveAdmissionService(registry("1"), repository);
    }

    @Test
    void returnsActiveWhileAdmissionKeyExists() {
        repository.activate("1", "token-a", Duration.ofSeconds(5));

        assertThat(service.getAdmission("1", " token-a ").status()).isEqualTo(Status.ACTIVE);
    }

    @Test
    void returnsNotFoundWhenAdmissionKeyIsAbsentOrExpired() {
        assertThat(service.getAdmission("1", "missing-token").status()).isEqualTo(Status.NOT_FOUND);
    }

    @Test
    void rejectsInvalidScreeningAndBlankToken() {
        assertThatThrownBy(() -> service.getAdmission("2", "token-a"))
                .isInstanceOf(InvalidScreeningException.class);
        assertThatThrownBy(() -> service.getAdmission("1", " "))
                .isInstanceOf(InvalidQueueTokenException.class);
    }

    private ScreeningRegistry registry(String... screeningIds) {
        QueueRegistrationProperties properties = new QueueRegistrationProperties();
        properties.setValidScreeningIds(Set.of(screeningIds));
        return new ScreeningRegistry(properties);
    }
}
