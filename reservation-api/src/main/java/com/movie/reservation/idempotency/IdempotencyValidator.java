package com.movie.reservation.idempotency;

import com.movie.reservation.config.ReservationProperties;
import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import com.movie.storage.ticket.IdempotencyClaimResult;
import com.movie.storage.ticket.IdempotencyRecordRepository;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class IdempotencyValidator {

    private final ReservationProperties properties;
    private final IdempotencyRecordRepository repository;

    public void validateAndClaim(String screeningId, String idempotencyKey, String fingerprint) {
        String key = requireValidKey(screeningId, idempotencyKey);
        IdempotencyClaimResult result = repository.claim(screeningId, key, fingerprint, properties.idempotency().ttl());
        switch (result) {
            case CLAIMED -> {
            }
            case DUPLICATE_IN_PROGRESS ->
                    throw new ReservationApiException(ReservationErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS, screeningId, key);
            case FINGERPRINT_CONFLICT ->
                    throw new ReservationApiException(ReservationErrorCode.IDEMPOTENCY_KEY_CONFLICT, screeningId, key);
            case STORE_UNAVAILABLE ->
                    throw new ReservationApiException(ReservationErrorCode.IDEMPOTENCY_STORE_UNAVAILABLE, screeningId, key);
        }
    }

    public String requireValidKey(String screeningId, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new ReservationApiException(ReservationErrorCode.IDEMPOTENCY_KEY_REQUIRED, screeningId, null);
        }
        String trimmed = idempotencyKey.trim();
        if (!Pattern.matches(properties.idempotencyKeyPattern(), trimmed)) {
            throw new ReservationApiException(ReservationErrorCode.IDEMPOTENCY_KEY_INVALID, screeningId, trimmed);
        }
        return trimmed;
    }
}
