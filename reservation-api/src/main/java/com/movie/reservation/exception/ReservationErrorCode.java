package com.movie.reservation.exception;

import org.springframework.http.HttpStatus;

public enum ReservationErrorCode {
    QUEUE_TOKEN_REQUIRED(HttpStatus.BAD_REQUEST, "Queue-Token header is required.", false),
    QUEUE_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "Queue-Token is invalid.", false),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required.", false),
    IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST, "Idempotency-Key is invalid.", false),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "Idempotency-Key was already used for a different request.", false),
    IDEMPOTENCY_STORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Idempotency store is unavailable.", true),
    SCREENING_NOT_FOUND(HttpStatus.NOT_FOUND, "Screening was not found.", false),
    INVALID_SEAT_SELECTION(HttpStatus.BAD_REQUEST, "Select one to four distinct seats.", false),
    SEAT_TAKEN(HttpStatus.CONFLICT, "A selected seat is unavailable.", true),
    NOT_ADMITTED(HttpStatus.FORBIDDEN, "Queue token is not admitted.", true),
    ALREADY_HOLDING(HttpStatus.CONFLICT, "Queue token already has an active hold.", false),
    ALREADY_RESERVED(HttpStatus.CONFLICT, "Queue token already has a reservation.", false),
    PAYMENT_NOT_PAID(HttpStatus.CONFLICT, "Payment is not paid.", true),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "Payment amount does not match the hold.", false),
    HOLD_EXPIRED(HttpStatus.CONFLICT, "Seat hold has expired.", false),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment was not found.", false),
    INVALID_WEBHOOK_SIGNATURE(HttpStatus.BAD_REQUEST, "Webhook signature is invalid.", false);

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
