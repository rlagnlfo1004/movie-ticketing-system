package com.movie.waiting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.movie.waiting.domain.QueueEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedisQueueRepositoryTest {

    private final RedisQueueRepository repository = new RedisQueueRepository("waiting:screening", new InMemoryQueueStore());

    @Test
    void insertsNewTokenWithScoreAndCardinality() {
        QueueEntry entry = repository.register("1", "token-a", 1000L);

        assertThat(entry.newlyRegistered()).isTrue();
        assertThat(entry.score()).isEqualTo(1000L);
        assertThat(repository.score("1", "token-a")).isEqualTo(1000D);
        assertThat(repository.cardinality("1")).isEqualTo(1L);
    }

    @Test
    void duplicateTokenKeepsOriginalScoreAndSingleMember() {
        repository.register("1", "token-a", 1000L);
        QueueEntry duplicate = repository.register("1", "token-a", 5000L);

        assertThat(duplicate.newlyRegistered()).isFalse();
        assertThat(duplicate.score()).isEqualTo(1000L);
        assertThat(repository.cardinality("1")).isEqualTo(1L);
    }

    @Test
    void popEarliestMatchesAscendingScoreOrder() {
        repository.addWithScore("1", "third", 3000L);
        repository.addWithScore("1", "first", 1000L);
        repository.addWithScore("1", "second", 2000L);

        List<QueueEntry> popped = repository.popEarliest("1", 3);

        assertThat(popped).extracting(QueueEntry::token)
                .containsExactly("first", "second", "third");
        assertThat(popped).extracting(QueueEntry::score)
                .containsExactly(1000L, 2000L, 3000L);
    }

    @Test
    void rankReturnsZeroBasedPositionAndNullForUnknownToken() {
        repository.addWithScore("1", "first", 1000L);
        repository.addWithScore("1", "second", 2000L);
        repository.addWithScore("1", "third", 3000L);

        assertThat(repository.rank("1", "first")).isZero();
        assertThat(repository.rank("1", "second")).isEqualTo(1L);
        assertThat(repository.rank("1", "missing")).isNull();
        assertThat(repository.cardinality("1")).isEqualTo(3L);
    }

    @Test
    void invalidScreeningHasNoRedisWriteWhenRepositoryIsNotCalled() {
        assertThat(repository.cardinality("invalid")).isZero();
        assertThat(repository.score("invalid", "token-a")).isNull();
    }
}
