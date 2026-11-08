package com.movie.reservation.reconcile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movie.reservation.client.MockPgClient;
import com.movie.reservation.client.MockPgClient.Payment;
import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import com.movie.reservation.repository.RedisSeatStateRepository;
import com.movie.reservation.repository.RedisSeatStateRepository.ConfirmationResult;
import com.movie.reservation.repository.RedisSeatStateRepository.RecoveryIntent;
import com.movie.reservation.reconcile.PaymentContextCodec.Context;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService implements ApplicationRunner {

    private final RedisSeatStateRepository redis;
    private final JdbcTemplate jdbc;
    private final MockPgClient pg;
    private final PaymentContextCodec codec;
    private final ObjectMapper mapper;

    private volatile boolean ready;
    private volatile String lastRunId;
    private volatile Result lastResult = new Result(List.of(), List.of(), 0, null);

    public record Result(List<String> confirmedPayments, List<String> cancelledPayments,
                         int restoredDbSeats, String redisRunId) {
    }

    private record DbReservation(String sid, String token, String paymentId, String reservationId) {
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureReady();
    }

    public boolean isReady() {
        return ready;
    }

    public Result lastResult() {
        return lastResult;
    }

    public void ensureReady() {
        try {
            String currentRun = redis.runId();
            if (ready && Objects.equals(currentRun, lastRunId)) return;
            synchronized (this) {
                currentRun = redis.runId();
                if (ready && Objects.equals(currentRun, lastRunId)) return;
                reconcileLocked(currentRun);
            }
        } catch (RuntimeException unavailable) {
            log.error("Reconciliation failed", unavailable);
            throw new ReservationApiException(ReservationErrorCode.RECONCILIATION_UNAVAILABLE, null, null);
        }
    }

    public synchronized Result forceReconcile() {
        try {
            return reconcileLocked(redis.runId());
        } catch (RuntimeException unavailable) {
            log.error("Manual reconciliation failed", unavailable);
            throw new ReservationApiException(ReservationErrorCode.RECONCILIATION_UNAVAILABLE, null, null);
        }
    }

    private Result reconcileLocked(String runId) {
        ready = false;
        Map<String, List<String>> dbSeats = new HashMap<>();
        jdbc.query("SELECT screening_id,seat_no FROM reservation_seats", (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                dbSeats.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>()).add(rs.getString(2)));
        redis.resetReserved("1", dbSeats.getOrDefault("1", List.of()));
        List<DbReservation> existing = jdbc.query("""
                SELECT screening_id,queue_token,payment_id,reservation_id FROM reservations
                """, (rs, row) -> new DbReservation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        Set<String> storedPayments = new HashSet<>();
        for (DbReservation reservation : existing) {
            storedPayments.add(reservation.paymentId());
            redis.seedCompleted(reservation.sid(), reservation.token(),
                    reservation.paymentId(), reservation.reservationId());
        }

        List<String> confirmed = new ArrayList<>();
        List<String> cancelled = new ArrayList<>();
        for (Payment payment : pg.listPayments()) {
            if (!"PAID".equals(payment.status()) || storedPayments.contains(payment.id())) continue;
            RecoveryIntent intent = recoveryIntent(payment);
            if (intent == null || payment.amount() == null || intent.amount() != payment.amount().total()
                    || !validIntent(intent)) {
                pg.cancel(payment.id(), "Missing or invalid seat context after Redis recovery");
                cancelled.add(payment.id());
                continue;
            }
            ConfirmationResult result = redis.recover(intent);
            if (result.code() == null && "CONFIRMED".equals(result.status())) {
                confirmed.add(payment.id());
            } else {
                pg.cancel(payment.id(), "Seats unavailable after Redis recovery");
                redis.cancel(intent.screeningId(), payment.id());
                cancelled.add(payment.id());
            }
        }
        waitForPersistence(confirmed);
        lastRunId = runId;
        lastResult = new Result(List.copyOf(confirmed), List.copyOf(cancelled),
                dbSeats.getOrDefault("1", List.of()).size(), runId);
        ready = true;
        return lastResult;
    }

    private RecoveryIntent recoveryIntent(Payment payment) {
        if (payment.customData() != null) {
            try {
                JsonNode custom = mapper.readTree(payment.customData());
                String encrypted = custom.path("ticketingContext").asText();
                Context context = codec.decode(encrypted);
                if (payment.id().equals(context.paymentId())) {
                    return new RecoveryIntent(context.screeningId(), context.holdId(), context.paymentId(),
                            context.queueToken(), context.idempotencyKey(), context.seats(), context.amount(),
                            payment.transactionId(), UUID.randomUUID().toString());
                }
            } catch (Exception invalidContext) {
                // A previous V4/V5 stream event can still provide the original intent.
            }
        }
        return redis.findStreamEvent("1", payment.id());
    }

    private boolean validIntent(RecoveryIntent intent) {
        if (!"1".equals(intent.screeningId()) || intent.seats() == null
                || intent.seats().isEmpty() || intent.seats().size() > 4
                || new HashSet<>(intent.seats()).size() != intent.seats().size()
                || intent.amount() != intent.seats().size() * 15_000) return false;
        return intent.seats().stream().allMatch(seat -> seat.matches("^[A-Y]-([1-9]|[1-3][0-9]|40)$"));
    }

    private void waitForPersistence(List<String> paymentIds) {
        for (String paymentId : paymentIds) {
            boolean persisted = false;
            for (int attempt = 0; attempt < 90; attempt++) {
                Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE payment_id=?",
                        Integer.class, paymentId);
                if (count != null && count > 0) {
                    persisted = true;
                    break;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Reconciliation interrupted", interrupted);
                }
            }
            if (!persisted) throw new IllegalStateException("Recovered reservation was not persisted: " + paymentId);
        }
    }
}
