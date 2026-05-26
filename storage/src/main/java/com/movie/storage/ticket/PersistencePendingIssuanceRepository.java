package com.movie.storage.ticket;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersistencePendingIssuanceRepository extends JpaRepository<PersistencePendingIssuance, Long> {

    Optional<PersistencePendingIssuance> findByScreeningIdAndQueueToken(String screeningId, String queueToken);

    Optional<PersistencePendingIssuance> findByScreeningIdAndIdempotencyKey(String screeningId, String idempotencyKey);
}
