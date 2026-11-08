package com.movie.reservation.service;

import com.movie.reservation.client.MockPgClient.Payment;
import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import com.movie.reservation.service.PaymentCompletionService.CompletionResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationConfirmationWriter {

    private final JdbcTemplate jdbc;

    private record LocalPayment(String holdId, String screeningId, String queueToken, String key,
                                Instant heldUntil, int amount, String status) {
    }

    @Transactional
    public CompletionResponse confirm(String paymentId, Payment pgPayment) {
        List<LocalPayment> local = jdbc.query("""
                SELECT h.hold_id,h.screening_id,h.queue_token,h.idempotency_key,h.held_until,p.amount,h.status
                FROM payments p JOIN seat_holds h ON h.hold_id=p.hold_id
                WHERE p.payment_id=? FOR UPDATE
                """, (rs, row) -> new LocalPayment(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5).toInstant(), rs.getInt(6), rs.getString(7)), paymentId);
        if (local.isEmpty()) throw new ReservationApiException(ReservationErrorCode.PAYMENT_NOT_FOUND, null, null);
        LocalPayment hold = local.getFirst();
        if (pgPayment.amount() == null || hold.amount() != pgPayment.amount().total()) {
            throw new ReservationApiException(ReservationErrorCode.PAYMENT_AMOUNT_MISMATCH, hold.screeningId(), hold.key());
        }
        List<String> prior = jdbc.query("SELECT reservation_id FROM reservations WHERE payment_id=?",
                (rs, row) -> rs.getString(1), paymentId);
        if (!prior.isEmpty()) return new CompletionResponse(prior.getFirst(), "CONFIRMED");
        if (!"HELD".equals(hold.status()) || !hold.heldUntil().isAfter(Instant.now())) {
            throw new ReservationApiException(ReservationErrorCode.HOLD_EXPIRED, hold.screeningId(), hold.key());
        }
        List<String> seats = jdbc.query("SELECT seat_no FROM screening_seats WHERE hold_id=? AND status='HELD' FOR UPDATE",
                (rs, row) -> rs.getString(1), hold.holdId());
        if (seats.isEmpty()) throw new ReservationApiException(ReservationErrorCode.HOLD_EXPIRED, hold.screeningId(), hold.key());
        String reservationId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO reservations (reservation_id,screening_id,queue_token,idempotency_key,hold_id,payment_id,created_at) VALUES (?,?,?,?,?,?,?)",
                reservationId, hold.screeningId(), hold.queueToken(), hold.key(), hold.holdId(), paymentId, Timestamp.from(now));
        for (String seat : seats) {
            jdbc.update("INSERT INTO reservation_seats (reservation_id,screening_id,seat_no) VALUES (?,?,?)",
                    reservationId, hold.screeningId(), seat);
        }
        jdbc.update("UPDATE screening_seats SET status='RESERVED',held_until=NULL WHERE hold_id=?", hold.holdId());
        jdbc.update("UPDATE seat_holds SET status='CONFIRMED' WHERE hold_id=?", hold.holdId());
        jdbc.update("UPDATE payments SET status='PAID',pg_tx_id=?,updated_at=? WHERE payment_id=?",
                pgPayment.transactionId(), Timestamp.from(now), paymentId);
        return new CompletionResponse(reservationId, "CONFIRMED");
    }
}
