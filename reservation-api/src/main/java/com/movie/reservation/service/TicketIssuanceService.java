package com.movie.reservation.service;

import com.movie.reservation.client.ActiveAdmissionClient;
import com.movie.reservation.client.ActiveAdmissionResult;
import com.movie.reservation.config.ReservationProperties;
import com.movie.reservation.dto.TicketIssuanceRequest;
import com.movie.reservation.dto.TicketIssuanceResponse;
import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import com.movie.storage.ticket.TicketInventoryRepository;
import com.movie.storage.ticket.TicketInventoryResult;
import com.movie.storage.ticket.ScreeningNotFoundException;
import com.movie.storage.ticket.TicketIssuance;
import com.movie.storage.ticket.TicketIssuanceCommandService;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class TicketIssuanceService {

    private final ActiveAdmissionClient activeAdmissionClient;
    private final TicketInventoryRepository inventoryRepository;
    private final TicketIssuanceCommandService commandService;
    private final ReservationProperties properties;

    public TicketIssuanceResponse issue(String screeningId, String queueToken, String idempotencyKey, TicketIssuanceRequest request) {
        String token = requireAndValidateQueueToken(screeningId, queueToken, idempotencyKey);
        String key = requireAndValidateIdempotencyKey(screeningId, idempotencyKey);
        validateQuantity(screeningId, key, request);

        Optional<TicketIssuance> idempotentReplay = commandService.findByIdempotencyKey(screeningId, key);
        if (idempotentReplay.isPresent()) {
            TicketIssuance existing = idempotentReplay.get();
            if (!existing.getQueueToken().equals(token) || existing.getQuantity() != request.quantity()) {
                throw new ReservationApiException(ReservationErrorCode.IDEMPOTENCY_KEY_CONFLICT, screeningId, key);
            }
            return TicketIssuanceResponse.from(existing);
        }

        commandService.findByQueueToken(screeningId, token).ifPresent(existing -> {
            throw new ReservationApiException(ReservationErrorCode.DUPLICATE_TICKET_ISSUANCE, screeningId, key);
        });

        ActiveAdmissionResult admission = activeAdmissionClient.validate(screeningId, token);
        if (!admission.active()) {
            throw new ReservationApiException(ReservationErrorCode.QUEUE_TOKEN_NOT_ACTIVE, screeningId, key);
        }

        TicketInventoryResult inventoryResult = inventoryRepository.decrementOne(screeningId, token);
        if (inventoryResult == TicketInventoryResult.DUPLICATE_TOKEN) {
            throw new ReservationApiException(ReservationErrorCode.DUPLICATE_TICKET_ISSUANCE, screeningId, key);
        }
        if (inventoryResult == TicketInventoryResult.SOLD_OUT) {
            throw new ReservationApiException(ReservationErrorCode.TICKET_SOLD_OUT, screeningId, key);
        }

        try {
            return TicketIssuanceResponse.from(commandService.saveSuccess(screeningId, token, key, request.quantity()));
        } catch (ScreeningNotFoundException exception) {
            throw new ReservationApiException(ReservationErrorCode.SCREENING_NOT_FOUND, screeningId, key);
        } catch (DataIntegrityViolationException exception) {
            throw new ReservationApiException(ReservationErrorCode.DUPLICATE_TICKET_ISSUANCE, screeningId, key);
        } catch (RuntimeException exception) {
            commandService.savePersistencePending(screeningId, token, key, request.quantity(), exception.getMessage());
            throw new ReservationApiException(ReservationErrorCode.TICKET_ISSUANCE_PERSISTENCE_FAILED, screeningId, key);
        }
    }

    private String requireAndValidateQueueToken(String screeningId, String queueToken, String idempotencyKey) {
        if (!StringUtils.hasText(queueToken)) {
            throw new ReservationApiException(ReservationErrorCode.QUEUE_TOKEN_REQUIRED, screeningId, idempotencyKey);
        }
        String trimmed = queueToken.trim();
        if (!Pattern.matches(properties.queueTokenPattern(), trimmed)) {
            throw new ReservationApiException(ReservationErrorCode.QUEUE_TOKEN_INVALID, screeningId, idempotencyKey);
        }
        return trimmed;
    }

    private String requireAndValidateIdempotencyKey(String screeningId, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new ReservationApiException(ReservationErrorCode.IDEMPOTENCY_KEY_REQUIRED, screeningId, null);
        }
        String trimmed = idempotencyKey.trim();
        if (!Pattern.matches(properties.idempotencyKeyPattern(), trimmed)) {
            throw new ReservationApiException(ReservationErrorCode.IDEMPOTENCY_KEY_INVALID, screeningId, trimmed);
        }
        return trimmed;
    }

    private void validateQuantity(String screeningId, String idempotencyKey, TicketIssuanceRequest request) {
        if (request == null || request.quantity() == null || request.quantity() != 1) {
            throw new ReservationApiException(ReservationErrorCode.INVALID_TICKET_QUANTITY, screeningId, idempotencyKey);
        }
    }
}
