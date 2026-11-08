package com.movie.reservation.service;

import com.movie.reservation.client.ActiveAdmissionClient;
import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SeatHoldService {

    private static final Pattern SEAT = Pattern.compile("^[A-Y]-([1-9]|[1-3][0-9]|40)$");
    private static final int PRICE = 15_000;

    private final JdbcTemplate jdbc;
    private final ActiveAdmissionClient admission;

    @Value("${reservation.hold-duration:30s}")
    private Duration holdDuration;

    @Value("${reservation.v1-lock-pause:0ms}")
    private Duration lockPause;

    @Value("${reservation.v1-screening-lock-pause:0ms}")
    private Duration screeningLockPause;

    public record HoldResponse(String holdId, String paymentId, int amount, Instant heldUntil) {
    }

    public record Seat(String seatNo, String status) {
    }

    public record SeatsResponse(List<Seat> seats) {
    }

    public record MineResponse(String status, String holdId, String paymentId, String reservationId) {
    }

    @Transactional
    public HoldResponse hold(String screeningId, String queueToken, String key, List<String> seats) {
        if (queueToken == null || !Pattern.matches("^[A-Za-z0-9._:-]{8,256}$", queueToken)) {
            throw error(ReservationErrorCode.QUEUE_TOKEN_INVALID, screeningId, key);
        }
        if (key == null || !Pattern.matches("^[A-Za-z0-9._:-]{8,128}$", key)) {
            throw error(ReservationErrorCode.IDEMPOTENCY_KEY_INVALID, screeningId, key);
        }
        if (seats == null || seats.isEmpty() || seats.size() > 4
                || new HashSet<>(seats).size() != seats.size()
                || seats.stream().anyMatch(seat -> seat == null || !SEAT.matcher(seat).matches())) {
            throw error(ReservationErrorCode.INVALID_SEAT_SELECTION, screeningId, key);
        }
        if (!admission.validate(screeningId, queueToken).active()) {
            throw error(ReservationErrorCode.NOT_ADMITTED, screeningId, key);
        }
        if (count("SELECT COUNT(*) FROM reservations WHERE screening_id=? AND queue_token=?", screeningId, queueToken) > 0) {
            throw error(ReservationErrorCode.ALREADY_RESERVED, screeningId, key);
        }
        List<HoldResponse> replay = jdbc.query("""
                SELECT h.hold_id,p.payment_id,p.amount,h.held_until,h.queue_token
                FROM seat_holds h JOIN payments p ON p.hold_id=h.hold_id
                WHERE h.screening_id=? AND h.idempotency_key=?
                """, (rs, row) -> {
            if (!queueToken.equals(rs.getString("queue_token"))) {
                throw error(ReservationErrorCode.IDEMPOTENCY_KEY_CONFLICT, screeningId, key);
            }
            return new HoldResponse(rs.getString("hold_id"), rs.getString("payment_id"),
                    rs.getInt("amount"), rs.getTimestamp("held_until").toInstant());
        }, screeningId, key);
        if (!replay.isEmpty()) {
            HoldResponse existing = replay.getFirst();
            List<String> heldSeats = jdbc.query("SELECT seat_no FROM screening_seats WHERE hold_id=?",
                    (rs, row) -> rs.getString(1), existing.holdId());
            if (!new HashSet<>(heldSeats).equals(new HashSet<>(seats))) {
                throw error(ReservationErrorCode.IDEMPOTENCY_KEY_CONFLICT, screeningId, key);
            }
            return existing;
        }
        if (count("SELECT COUNT(*) FROM seat_holds WHERE screening_id=? AND queue_token=? AND status='HELD' AND held_until>NOW(6)", screeningId, queueToken) > 0) {
            throw error(ReservationErrorCode.ALREADY_HOLDING, screeningId, key);
        }

        // V1: 요청 좌석 순서 그대로 잠그고, FK의 상영 S-lock 뒤에 상영 행 X-lock을 잡는다.
        for (int index = 0; index < seats.size(); index++) {
            String seat = seats.get(index);
            List<String> status = jdbc.query("""
                    SELECT status FROM screening_seats WHERE screening_id=? AND seat_no=? FOR UPDATE
                    """, (rs, row) -> rs.getString(1), screeningId, seat);
            if (status.isEmpty() || !"AVAILABLE".equals(status.getFirst())) {
                throw error(ReservationErrorCode.SEAT_TAKEN, screeningId, key);
            }
            if (index == 0 && seats.size() > 1) pause(lockPause);
        }
        String holdId = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant until = now.plus(holdDuration);
        try {
            jdbc.update("INSERT INTO seat_holds (hold_id,screening_id,queue_token,idempotency_key,status,held_until,created_at) VALUES (?,?,?,?,'HELD',?,?)",
                    holdId, screeningId, queueToken, key, Timestamp.from(until), Timestamp.from(now));
        } catch (DuplicateKeyException duplicate) {
            throw error(ReservationErrorCode.ALREADY_HOLDING, screeningId, key);
        }
        pause(screeningLockPause);
        for (String seat : seats) {
            jdbc.update("UPDATE screening_seats SET status='HELD',hold_id=?,held_until=? WHERE screening_id=? AND seat_no=?",
                    holdId, Timestamp.from(until), screeningId, seat);
        }
        jdbc.update("UPDATE screenings SET remaining_seat_count=remaining_seat_count-? WHERE id=?", seats.size(), screeningId);
        jdbc.update("INSERT INTO payments (payment_id,hold_id,screening_id,queue_token,amount,status,created_at,updated_at) VALUES (?,?,?,?,?,'READY',?,?)",
                paymentId, holdId, screeningId, queueToken, seats.size() * PRICE, Timestamp.from(now), Timestamp.from(now));
        return new HoldResponse(holdId, paymentId, seats.size() * PRICE, until);
    }

    @Transactional(readOnly = true)
    public SeatsResponse seats(String screeningId) {
        List<Seat> seats = jdbc.query("SELECT seat_no,status FROM screening_seats WHERE screening_id=? ORDER BY seat_no",
                (rs, row) -> new Seat(rs.getString(1), rs.getString(2)), screeningId);
        if (seats.isEmpty()) throw error(ReservationErrorCode.SCREENING_NOT_FOUND, screeningId, null);
        return new SeatsResponse(seats);
    }

    @Transactional(readOnly = true)
    public MineResponse mine(String screeningId, String queueToken) {
        if (queueToken == null || queueToken.isBlank()) {
            throw error(ReservationErrorCode.QUEUE_TOKEN_REQUIRED, screeningId, null);
        }
        List<MineResponse> results = jdbc.query("""
                SELECT h.status,h.hold_id,p.payment_id,r.reservation_id
                FROM seat_holds h JOIN payments p ON p.hold_id=h.hold_id
                LEFT JOIN reservations r ON r.hold_id=h.hold_id
                WHERE h.screening_id=? AND h.queue_token=?
                ORDER BY h.created_at DESC LIMIT 1
                """, (rs, row) -> new MineResponse(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                screeningId, queueToken);
        return results.isEmpty() ? new MineResponse("NOT_FOUND", null, null, null) : results.getFirst();
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private ReservationApiException error(ReservationErrorCode code, String screeningId, String key) {
        return new ReservationApiException(code, screeningId, key);
    }

    private void pause(Duration duration) {
        if (duration.isZero()) return;
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("V1 lock pause interrupted", interrupted);
        }
    }
}
