package com.movie.storage.ticket;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketIssuanceRepository extends JpaRepository<TicketIssuance, Long> {

    Optional<TicketIssuance> findByScreeningIdAndQueueToken(String screeningId, String queueToken);

    Optional<TicketIssuance> findByScreeningIdAndIdempotencyKey(String screeningId, String idempotencyKey);

    List<TicketIssuance> findAllByScreeningIdOrderByIssuedAtDesc(String screeningId);
}
