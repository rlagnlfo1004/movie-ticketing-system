package com.movie.worker;

import java.util.List;

public record ReservationEvent(
        String screeningId,
        String reservationId,
        String holdId,
        String paymentId,
        String queueToken,
        String idempotencyKey,
        int amount,
        String transactionId,
        List<String> seats
) {
}
