package com.movie.storage.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.movie.storage.screening.Screening;
import com.movie.storage.screening.ScreeningRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
class TicketIssuanceRepositoryTest {

    @Autowired
    TicketIssuanceRepository repository;

    @Autowired
    ScreeningRepository screeningRepository;

    @Test
    void findsByQueueTokenAndIdempotencyKey() {
        seedScreening("1");
        TicketIssuance saved = repository.saveAndFlush(new TicketIssuance("1", "active-token-123", "issuance-key-123", 1));

        assertThat(repository.findByScreeningIdAndQueueToken("1", "active-token-123")).contains(saved);
        assertThat(repository.findByScreeningIdAndIdempotencyKey("1", "issuance-key-123")).contains(saved);
        assertThat(repository.findAllByScreeningIdOrderByIssuedAtDesc("1")).contains(saved);
    }

    @Test
    void enforcesUniqueQueueTokenPerScreening() {
        seedScreening("1");
        repository.saveAndFlush(new TicketIssuance("1", "active-token-123", "issuance-key-123", 1));

        assertThatThrownBy(() -> repository.saveAndFlush(new TicketIssuance("1", "active-token-123", "other-key-123", 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesUniqueIdempotencyKeyPerScreening() {
        seedScreening("1");
        repository.saveAndFlush(new TicketIssuance("1", "active-token-123", "issuance-key-123", 1));

        assertThatThrownBy(() -> repository.saveAndFlush(new TicketIssuance("1", "other-token-123", "issuance-key-123", 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsTicketForMissingScreening() {
        assertThatThrownBy(() -> repository.saveAndFlush(new TicketIssuance("missing", "active-token-123", "issuance-key-123", 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void seedScreening(String id) {
        screeningRepository.saveAndFlush(new Screening(id, "Demo Movie", Instant.parse("2026-05-12T10:00:00Z"), 100));
    }
}
