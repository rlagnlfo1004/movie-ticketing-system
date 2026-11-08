package com.movie.reservation.service;

import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import com.movie.reservation.repository.RedisSeatStateRepository;
import com.movie.reservation.repository.RedisSeatStateRepository.HoldData;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SeatHoldService {

    private static final Pattern SEAT = Pattern.compile("^[A-Y]-([1-9]|[1-3][0-9]|40)$");

    private final RedisSeatStateRepository repository;

    @Value("${reservation.hold-duration:30s}")
    private Duration holdDuration;

    public record HoldResponse(String holdId, String paymentId, int amount, Instant heldUntil) {
    }

    public record Seat(String seatNo, String status) {
    }

    public record SeatsResponse(List<Seat> seats) {
    }

    public record MineResponse(String status, String holdId, String paymentId, String reservationId) {
    }

    public HoldResponse hold(String screeningId, String queueToken, String key, List<String> seats) {
        if (queueToken == null || !Pattern.matches("^[A-Za-z0-9._:-]{8,256}$", queueToken)) {
            throw error(ReservationErrorCode.QUEUE_TOKEN_INVALID, screeningId, key);
        }
        if (key == null || !Pattern.matches("^[A-Za-z0-9._:-]{8,128}$", key)) {
            throw error(ReservationErrorCode.IDEMPOTENCY_KEY_INVALID, screeningId, key);
        }
        if (seats == null || seats.isEmpty() || seats.size() > 4 || new HashSet<>(seats).size() != seats.size()
                || seats.stream().anyMatch(seat -> seat == null || !SEAT.matcher(seat).matches())) {
            throw error(ReservationErrorCode.INVALID_SEAT_SELECTION, screeningId, key);
        }
        String holdId = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();
        Instant heldUntil = Instant.now().plus(holdDuration);
        int amount = seats.size() * 15_000;
        long result = repository.hold(screeningId, queueToken, key, seats, holdId, paymentId, amount, heldUntil, holdDuration);
        if (result == 1) return new HoldResponse(holdId, paymentId, amount, heldUntil);
        if (result == -1) throw error(ReservationErrorCode.NOT_ADMITTED, screeningId, key);
        if (result == -2) {
            HoldData previous = repository.holdForToken(screeningId, queueToken);
            if (previous != null && previous.key().equals(key)
                    && new HashSet<>(previous.seats()).equals(new HashSet<>(seats))) {
                return new HoldResponse(previous.holdId(), previous.paymentId(), previous.amount(), previous.heldUntil());
            }
            throw error(ReservationErrorCode.ALREADY_HOLDING, screeningId, key);
        }
        if (result == -3) throw error(ReservationErrorCode.ALREADY_RESERVED, screeningId, key);
        throw error(ReservationErrorCode.SEAT_TAKEN, screeningId, key);
    }

    public SeatsResponse seats(String screeningId) {
        if (!"1".equals(screeningId)) throw error(ReservationErrorCode.SCREENING_NOT_FOUND, screeningId, null);
        List<String> seatNumbers = new ArrayList<>(1000);
        for (char row = 'A'; row <= 'Y'; row++) {
            for (int number = 1; number <= 40; number++) seatNumbers.add(row + "-" + number);
        }
        List<Seat> seats = repository.seats(screeningId, seatNumbers).stream()
                .map(state -> new Seat(state.seatNo(), state.status())).toList();
        return new SeatsResponse(seats);
    }

    public MineResponse mine(String screeningId, String queueToken) {
        if (queueToken == null || queueToken.isBlank()) {
            throw error(ReservationErrorCode.QUEUE_TOKEN_REQUIRED, screeningId, null);
        }
        String reservationId = repository.reservationForToken(screeningId, queueToken);
        if (reservationId != null) return new MineResponse("CONFIRMED", null, null, reservationId);
        if (repository.cancelledForToken(screeningId, queueToken)) {
            return new MineResponse("CANCELLED", null, null, null);
        }
        HoldData active = repository.holdForToken(screeningId, queueToken);
        if (active != null) return new MineResponse("HELD", active.holdId(), active.paymentId(), null);
        HoldData expired = repository.lastHoldForToken(screeningId, queueToken);
        if (expired != null) return new MineResponse("EXPIRED", expired.holdId(), expired.paymentId(), null);
        return new MineResponse("NOT_FOUND", null, null, null);
    }

    private ReservationApiException error(ReservationErrorCode code, String sid, String key) {
        return new ReservationApiException(code, sid, key);
    }
}
