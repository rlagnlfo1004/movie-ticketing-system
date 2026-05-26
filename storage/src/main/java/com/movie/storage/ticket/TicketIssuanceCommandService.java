package com.movie.storage.ticket;

import com.movie.storage.screening.ScreeningRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TicketIssuanceCommandService {

    private final TicketIssuanceRepository ticketIssuanceRepository;
    private final PersistencePendingIssuanceRepository persistencePendingIssuanceRepository;
    private final ScreeningRepository screeningRepository;

    @Transactional(readOnly = true)
    public Optional<TicketIssuance> findByQueueToken(String screeningId, String queueToken) {
        return ticketIssuanceRepository.findByScreeningIdAndQueueToken(screeningId, queueToken);
    }

    @Transactional(readOnly = true)
    public Optional<TicketIssuance> findByIdempotencyKey(String screeningId, String idempotencyKey) {
        return ticketIssuanceRepository.findByScreeningIdAndIdempotencyKey(screeningId, idempotencyKey);
    }

    @Transactional
    public TicketIssuance saveSuccess(String screeningId, String queueToken, String idempotencyKey, int quantity) {
        if (!screeningRepository.existsById(screeningId)) {
            throw new ScreeningNotFoundException(screeningId);
        }
        return ticketIssuanceRepository.saveAndFlush(new TicketIssuance(screeningId, queueToken, idempotencyKey, quantity));
    }

    @Transactional(readOnly = true)
    public java.util.List<TicketIssuance> findHistory(String screeningId, String queueToken) {
        if (!screeningRepository.existsById(screeningId)) {
            throw new ScreeningNotFoundException(screeningId);
        }
        if (queueToken == null || queueToken.isBlank()) {
            return ticketIssuanceRepository.findAllByScreeningIdOrderByIssuedAtDesc(screeningId);
        }
        return ticketIssuanceRepository.findByScreeningIdAndQueueToken(screeningId, queueToken)
                .map(java.util.List::of)
                .orElseGet(java.util.List::of);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistencePendingIssuance savePersistencePending(
            String screeningId,
            String queueToken,
            String idempotencyKey,
            int quantity,
            String failureReason
    ) {
        return persistencePendingIssuanceRepository.saveAndFlush(
                new PersistencePendingIssuance(screeningId, queueToken, idempotencyKey, quantity, failureReason)
        );
    }
}
