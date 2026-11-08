package com.movie.worker;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationBatchWriter {

    private final JdbcTemplate jdbc;

    public boolean databaseAvailable() {
        try {
            Integer result = jdbc.queryForObject("SELECT 1", Integer.class);
            return result != null && result == 1;
        } catch (Exception unavailable) {
            return false;
        }
    }

    @Transactional
    public void persist(List<ReservationEvent> events) {
        Timestamp now = Timestamp.from(Instant.now());
        List<Object[]> holds = new ArrayList<>(events.size());
        List<Object[]> payments = new ArrayList<>(events.size());
        List<Object[]> reservations = new ArrayList<>(events.size());
        for (ReservationEvent event : events) {
            holds.add(new Object[] { event.holdId(), event.screeningId(), event.queueToken(),
                    event.idempotencyKey(), now, now });
            payments.add(new Object[] { event.paymentId(), event.holdId(), event.screeningId(),
                    event.queueToken(), event.amount(), event.transactionId(), now, now });
            reservations.add(new Object[] { event.reservationId(), event.screeningId(), event.queueToken(),
                    event.idempotencyKey(), event.holdId(), event.paymentId(), now });
        }
        jdbc.batchUpdate("""
                INSERT IGNORE INTO seat_holds (hold_id,screening_id,queue_token,idempotency_key,status,held_until,created_at)
                VALUES (?,?,?,?,'CONFIRMED',?,?)
                """, holds);
        jdbc.batchUpdate("""
                INSERT IGNORE INTO payments (payment_id,hold_id,screening_id,queue_token,amount,status,pg_tx_id,created_at,updated_at)
                VALUES (?,?,?,?,?,'PAID',?,?,?)
                """, payments);
        int[] inserted = jdbc.batchUpdate("""
                INSERT IGNORE INTO reservations (reservation_id,screening_id,queue_token,idempotency_key,hold_id,payment_id,created_at)
                VALUES (?,?,?,?,?,?,?)
                """, reservations);
        List<Object[]> holdSeats = new ArrayList<>();
        List<Object[]> reservationSeats = new ArrayList<>();
        List<Object[]> seatUpdates = new ArrayList<>();
        List<Object[]> screeningUpdates = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            ReservationEvent event = events.get(index);
            if (inserted[index] == 0) {
                String existing = jdbc.queryForObject("SELECT reservation_id FROM reservations WHERE payment_id=?",
                        String.class, event.paymentId());
                if (!event.reservationId().equals(existing)) {
                    throw new IllegalStateException("Reservation insert conflicted with another payment");
                }
                continue;
            }
            screeningUpdates.add(new Object[] { event.seats().size(), event.screeningId() });
            for (String seat : event.seats()) {
                holdSeats.add(new Object[] { event.holdId(), event.screeningId(), seat });
                reservationSeats.add(new Object[] { event.reservationId(), event.screeningId(), seat });
                seatUpdates.add(new Object[] { event.holdId(), event.screeningId(), seat });
            }
        }
        jdbc.batchUpdate("INSERT IGNORE INTO seat_hold_seats (hold_id,screening_id,seat_no) VALUES (?,?,?)", holdSeats);
        int[] seatInserts = jdbc.batchUpdate("INSERT IGNORE INTO reservation_seats (reservation_id,screening_id,seat_no) VALUES (?,?,?)", reservationSeats);
        for (int count : seatInserts) {
            if (count == 0) throw new IllegalStateException("Seat was already persisted for another reservation");
        }
        jdbc.batchUpdate("UPDATE screening_seats SET status='RESERVED',hold_id=?,held_until=NULL WHERE screening_id=? AND seat_no=?",
                seatUpdates);
        jdbc.batchUpdate("UPDATE screenings SET remaining_seat_count=remaining_seat_count-? WHERE id=?",
                screeningUpdates);
    }
}
