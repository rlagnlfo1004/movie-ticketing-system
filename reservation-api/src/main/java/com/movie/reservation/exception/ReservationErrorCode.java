package com.movie.reservation.exception;

import org.springframework.http.HttpStatus;

public enum ReservationErrorCode {
    QUEUE_TOKEN_REQUIRED(HttpStatus.BAD_REQUEST, "Queue-Token header is required.", false),
    QUEUE_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "Queue-Token is invalid.", false),
    QUEUE_TOKEN_NOT_ACTIVE(HttpStatus.FORBIDDEN, "Queue-Token is not active.", true),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required.", false),
    IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST, "Idempotency-Key is invalid.", false),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "Idempotency-Key was already used for a different request.", false),
    IDEMPOTENCY_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "Idempotency request is already in progress.", true),
    IDEMPOTENCY_STORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Idempotency store is unavailable.", true),
    INVALID_TICKET_QUANTITY(HttpStatus.BAD_REQUEST, "Ticket quantity must be exactly 1.", false),
    SCREENING_NOT_FOUND(HttpStatus.NOT_FOUND, "Screening was not found.", false),
    DUPLICATE_TICKET_ISSUANCE(HttpStatus.CONFLICT, "A ticket has already been issued for this queue token.", false),
    TICKET_SOLD_OUT(HttpStatus.CONFLICT, "Ticket inventory is sold out.", true),
    TICKET_ISSUANCE_PERSISTENCE_FAILED(HttpStatus.CONFLICT, "Ticket issuance requires persistence recovery.", true);

    private final HttpStatus status;
    private final String message;
    private final boolean retryable;

    ReservationErrorCode(HttpStatus status, String message, boolean retryable) {
        this.status = status;
        this.message = message;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }

    public boolean retryable() {
        return retryable;
    }
}
