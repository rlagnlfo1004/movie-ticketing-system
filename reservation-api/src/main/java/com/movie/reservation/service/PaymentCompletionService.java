package com.movie.reservation.service;

import com.movie.reservation.client.MockPgClient;
import com.movie.reservation.client.MockPgClient.Payment;
import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentCompletionService {

    private final MockPgClient pg;
    private final ReservationConfirmationWriter writer;

    public record CompletionResponse(String reservationId, String status) {
    }

    public CompletionResponse complete(String paymentId) {
        Payment payment = pg.find(paymentId);
        if (!paymentId.equals(payment.id()) || !"PAID".equals(payment.status())) {
            throw new ReservationApiException(ReservationErrorCode.PAYMENT_NOT_PAID, null, null);
        }
        return writer.confirm(paymentId, payment);
    }
}
