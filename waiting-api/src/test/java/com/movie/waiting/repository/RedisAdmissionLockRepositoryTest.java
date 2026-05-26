package com.movie.waiting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RedisAdmissionLockRepositoryTest {

    private final InMemoryAdmissionLockStore store = new InMemoryAdmissionLockStore();
    private final RedisAdmissionLockRepository repository = new RedisAdmissionLockRepository("waiting:screening", store);

    @Test
    void lockKeyUsesScreeningScopedNamespace() {
        assertThat(repository.lockKey("1")).isEqualTo("waiting:screening:1:admission:lock");
    }

    @Test
    void acquireUsesSetNxSemantics() {
        assertThat(repository.acquire("1", "owner-a", Duration.ofSeconds(5))).isTrue();
        assertThat(repository.acquire("1", "owner-b", Duration.ofSeconds(5))).isFalse();
    }

    @Test
    void releaseOnlySucceedsForCurrentOwner() {
        repository.acquire("1", "owner-a", Duration.ofSeconds(5));

        assertThat(repository.release("1", "owner-b")).isFalse();
        assertThat(repository.acquire("1", "owner-c", Duration.ofSeconds(5))).isFalse();
        assertThat(repository.release("1", "owner-a")).isTrue();
        assertThat(repository.acquire("1", "owner-c", Duration.ofSeconds(5))).isTrue();
    }
}
