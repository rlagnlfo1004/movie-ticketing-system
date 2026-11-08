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
    private final RedisReservationConfirmationService writer;

    public record CompletionResponse(String reservationId, String status) {
    }

    public CompletionResponse complete(String paymentId) {
        Payment payment = pg.find(paymentId);
        if (!paymentId.equals(payment.id())) {
            throw new ReservationApiException(ReservationErrorCode.PAYMENT_NOT_PAID, null, null);
        }
        if ("CANCELLED".equals(payment.status())) {
            writer.cancel(paymentId);
            return new CompletionResponse(null, "CANCELLED");
        }
        if (!"PAID".equals(payment.status())) {
            throw new ReservationApiException(ReservationErrorCode.PAYMENT_NOT_PAID, null, null);
        }
        try {
            return writer.confirm(paymentId, payment);
        } catch (ReservationApiException exception) {
            if (exception.getErrorCode() != ReservationErrorCode.SEAT_TAKEN
                    && exception.getErrorCode() != ReservationErrorCode.HOLD_EXPIRED) {
                throw exception;
            }
            pg.cancel(paymentId, "Seat hold expired or seats unavailable");
            writer.cancel(paymentId);
            return new CompletionResponse(null, "CANCELLED");
        }
    }
}
