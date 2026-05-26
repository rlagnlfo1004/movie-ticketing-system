package com.movie.waiting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RedisActiveAdmissionRepositoryTest {

    private final InMemoryActiveAdmissionStore store = new InMemoryActiveAdmissionStore();
    private final RedisActiveAdmissionRepository repository = new RedisActiveAdmissionRepository(
            "waiting:screening",
            store,
            Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC)
    );

    @Test
    void activeKeyUsesScreeningScopedNamespace() {
        assertThat(repository.activeKey("1", "token-a"))
                .isEqualTo("waiting:screening:1:active:token-a");
    }

    @Test
    void activatesTokenWithTtlBackedKeyAndActiveIndex() {
        boolean activated = repository.activate("1", "token-a", Duration.ofSeconds(5));

        assertThat(activated).isTrue();
        assertThat(repository.exists("1", "token-a")).isTrue();
        assertThat(repository.countActive("1")).isEqualTo(1L);
        assertThat(store.indexedTokens("waiting:screening:1:active:index")).containsExactly("token-a");
    }

    @Test
    void duplicateActiveTokenDoesNotCreateAnotherIndexEntry() {
        repository.activate("1", "token-a", Duration.ofSeconds(5));

        boolean duplicate = repository.activate("1", "token-a", Duration.ofSeconds(5));

        assertThat(duplicate).isFalse();
        assertThat(repository.countActive("1")).isEqualTo(1L);
    }

    @Test
    void activeCountCleansExpiredIndexEntries() {
        repository.activate("1", "token-a", Duration.ZERO);
        store.expire(repository.activeKey("1", "token-a"));

        assertThat(repository.countActive("1")).isZero();
        assertThat(store.indexedTokens(repository.activeIndexKey("1"))).isEmpty();
    }
}
