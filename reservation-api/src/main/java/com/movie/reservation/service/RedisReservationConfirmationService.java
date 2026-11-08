package com.movie.reservation.service;

import com.movie.reservation.client.MockPgClient.Payment;
import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import com.movie.reservation.repository.RedisSeatStateRepository;
import com.movie.reservation.repository.RedisSeatStateRepository.ConfirmationResult;
import com.movie.reservation.service.PaymentCompletionService.CompletionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RedisReservationConfirmationService {

    private final RedisSeatStateRepository repository;

    public CompletionResponse confirm(String paymentId, Payment pgPayment) {
        String sid = repository.screeningForPayment(paymentId);
        if (sid == null) throw new ReservationApiException(ReservationErrorCode.HOLD_EXPIRED, null, null);
        if (pgPayment.amount() == null || pgPayment.transactionId() == null) {
            throw new ReservationApiException(ReservationErrorCode.PAYMENT_NOT_PAID, sid, null);
        }
        ConfirmationResult result = repository.confirm(sid, paymentId, pgPayment.amount().total(), pgPayment.transactionId());
        if (result.code() != null) {
            ReservationErrorCode code = switch (result.code()) {
                case "AMOUNT_MISMATCH" -> ReservationErrorCode.PAYMENT_AMOUNT_MISMATCH;
                case "EXPIRED_TAKEN" -> ReservationErrorCode.SEAT_TAKEN;
                default -> ReservationErrorCode.HOLD_EXPIRED;
            };
            throw new ReservationApiException(code, sid, null);
        }
        return new CompletionResponse(result.reservationId(), result.status());
    }

    public void cancel(String paymentId) {
        String sid = repository.screeningForPayment(paymentId);
        if (sid != null) repository.cancel(sid, paymentId);
    }
}
