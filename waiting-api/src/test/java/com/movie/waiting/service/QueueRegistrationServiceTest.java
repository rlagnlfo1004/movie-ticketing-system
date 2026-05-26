package com.movie.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movie.waiting.config.QueueRegistrationProperties;
import com.movie.waiting.domain.QueueEntry;
import com.movie.waiting.exception.InvalidScreeningException;
import com.movie.waiting.repository.RedisQueueRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class QueueRegistrationServiceTest {

    private final RedisQueueRepository repository = Mockito.mock(RedisQueueRepository.class);

    @Test
    void generatesUuidTokenAfterScreeningValidation() {
        QueueRegistrationService service = service("generated-token");
        when(repository.register("1", "generated-token", 1000L))
                .thenReturn(new QueueEntry("1", "generated-token", 1000L, true, true));

        QueueEntry entry = service.register("1", null);

        assertThat(entry.token()).isEqualTo("generated-token");
        verify(repository).register("1", "generated-token", 1000L);
    }

    @Test
    void acceptsProvidedToken() {
        QueueRegistrationService service = service("unused-generated-token");
        when(repository.register("1", "client-token", 1000L))
                .thenReturn(new QueueEntry("1", "client-token", 1000L, true, true));

        QueueEntry entry = service.register("1", " client-token ");

        assertThat(entry.token()).isEqualTo("client-token");
        verify(repository).register("1", "client-token", 1000L);
    }

    @Test
    void duplicateRegistrationReturnsExistingScore() {
        QueueRegistrationService service = service("unused-generated-token");
        when(repository.register("1", "client-token", 1000L))
                .thenReturn(new QueueEntry("1", "client-token", 500L, true, false));

        QueueEntry entry = service.register("1", "client-token");

        assertThat(entry.newlyRegistered()).isFalse();
        assertThat(entry.score()).isEqualTo(500L);
    }

    @Test
    void invalidScreeningRejectsBeforeTokenGenerationAndRedisWrite() {
        AtomicInteger tokenGenerations = new AtomicInteger();
        QueueRegistrationService service = new QueueRegistrationService(
                registry("1"),
                repository,
                Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC),
                () -> {
                    tokenGenerations.incrementAndGet();
                    return UUID.randomUUID().toString();
                }
        );

        assertThatThrownBy(() -> service.register("missing", null))
                .isInstanceOf(InvalidScreeningException.class);
        assertThat(tokenGenerations).hasValue(0);
        verify(repository, never()).register(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());
    }

    private QueueRegistrationService service(String generatedToken) {
        return new QueueRegistrationService(
                registry("1"),
                repository,
                Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC),
                () -> generatedToken
        );
    }

    private ScreeningRegistry registry(String... screeningIds) {
        QueueRegistrationProperties properties = new QueueRegistrationProperties();
        properties.setValidScreeningIds(Set.of(screeningIds));
        return new ScreeningRegistry(properties);
    }
}
