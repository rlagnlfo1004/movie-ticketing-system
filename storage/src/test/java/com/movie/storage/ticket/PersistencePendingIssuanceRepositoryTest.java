package com.movie.storage.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
class PersistencePendingIssuanceRepositoryTest {

    @Autowired
    PersistencePendingIssuanceRepository repository;

    @Test
    void findsByQueueTokenAndIdempotencyKey() {
        PersistencePendingIssuance saved = repository.saveAndFlush(
                new PersistencePendingIssuance("1", "active-token-123", "issuance-key-123", 1, "db failed")
        );

        assertThat(repository.findByScreeningIdAndQueueToken("1", "active-token-123")).contains(saved);
        assertThat(repository.findByScreeningIdAndIdempotencyKey("1", "issuance-key-123")).contains(saved);
    }

    @Test
    void enforcesUniqueQueueTokenPerScreening() {
        repository.saveAndFlush(new PersistencePendingIssuance("1", "active-token-123", "issuance-key-123", 1, "db failed"));

        assertThatThrownBy(() -> repository.saveAndFlush(
                new PersistencePendingIssuance("1", "active-token-123", "other-key-123", 1, "db failed")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesUniqueIdempotencyKeyPerScreening() {
        repository.saveAndFlush(new PersistencePendingIssuance("1", "active-token-123", "issuance-key-123", 1, "db failed"));

        assertThatThrownBy(() -> repository.saveAndFlush(
                new PersistencePendingIssuance("1", "other-token-123", "issuance-key-123", 1, "db failed")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }
}
