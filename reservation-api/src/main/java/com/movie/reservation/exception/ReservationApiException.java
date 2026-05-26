package com.movie.reservation.exception;

import lombok.Getter;

@Getter
public class ReservationApiException extends RuntimeException {

    private final ReservationErrorCode errorCode;
    private final String screeningId;
    private final String idempotencyKey;

    public ReservationApiException(ReservationErrorCode errorCode, String screeningId, String idempotencyKey) {
        super(errorCode.message());
        this.errorCode = errorCode;
        this.screeningId = screeningId;
        this.idempotencyKey = idempotencyKey;
    }
}
