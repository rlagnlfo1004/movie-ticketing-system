package com.movie.reservation.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movie.storage.seat.SeatRedisKeys;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisSeatStateRepository {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    private static DefaultRedisScript<Long> holdScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/hold-v5.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private static DefaultRedisScript<String> stringScript(String location) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(String.class);
        return script;
    }

    public long hold(String sid, String token, String idempotencyKey, List<String> seats,
                     String holdId, String paymentId, int amount, Instant heldUntil, Duration duration) {
        List<String> keys = new ArrayList<>();
        keys.add(SeatRedisKeys.reserved(sid));
        keys.add(SeatRedisKeys.tokenHold(sid, token));
        keys.add(SeatRedisKeys.tokenReservation(sid, token));
        keys.add(SeatRedisKeys.holdData(sid, holdId));
        keys.add(SeatRedisKeys.paymentHold(sid, paymentId));
        keys.add(SeatRedisKeys.paymentScreening(paymentId));
        keys.add(SeatRedisKeys.tokenLastHold(sid, token));
        keys.add(SeatRedisKeys.activeAdmission(sid, token));
        for (String seat : seats) keys.add(SeatRedisKeys.hold(sid, seat));
        try {
            Long result = redis.execute(holdScript(), keys,
                    holdId, paymentId, token, idempotencyKey, String.valueOf(amount),
                    String.valueOf(heldUntil.toEpochMilli()), String.valueOf(duration.toMillis()),
                    String.valueOf(duration.plusSeconds(60).toMillis()), mapper.writeValueAsString(seats), sid);
            if (result == null) throw new IllegalStateException("No Redis hold result");
            return result;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid seat list", exception);
        }
    }

    public record HoldData(String holdId, String paymentId, String token, String key,
                           int amount, Instant heldUntil, List<String> seats) {
    }

    public HoldData holdForToken(String sid, String token) {
        String holdId = redis.opsForValue().get(SeatRedisKeys.tokenHold(sid, token));
        if (holdId == null) return null;
        return holdById(sid, holdId);
    }

    public HoldData lastHoldForToken(String sid, String token) {
        String holdId = redis.opsForValue().get(SeatRedisKeys.tokenLastHold(sid, token));
        return holdId == null ? null : holdById(sid, holdId);
    }

    private HoldData holdById(String sid, String holdId) {
        Map<Object, Object> data = redis.opsForHash().entries(SeatRedisKeys.holdData(sid, holdId));
        if (data.isEmpty()) return null;
        try {
            JsonNode seats = mapper.readTree(String.valueOf(data.get("seats")));
            List<String> values = new ArrayList<>();
            seats.forEach(item -> values.add(item.asText()));
            return new HoldData(holdId, String.valueOf(data.get("paymentId")),
                    String.valueOf(data.get("token")), String.valueOf(data.get("idempotencyKey")),
                    Integer.parseInt(String.valueOf(data.get("amount"))),
                    Instant.ofEpochMilli(Long.parseLong(String.valueOf(data.get("heldUntil")))), values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid Redis hold data", exception);
        }
    }

    public record SeatState(String seatNo, String status) {
    }

    public List<SeatState> seats(String sid, List<String> seatNumbers) {
        Set<String> reserved = redis.opsForSet().members(SeatRedisKeys.reserved(sid));
        List<String> keys = seatNumbers.stream().map(seat -> SeatRedisKeys.hold(sid, seat)).toList();
        List<String> held = redis.opsForValue().multiGet(keys);
        List<SeatState> result = new ArrayList<>(seatNumbers.size());
        for (int i = 0; i < seatNumbers.size(); i++) {
            String seat = seatNumbers.get(i);
            String status = reserved != null && reserved.contains(seat) ? "RESERVED"
                    : held != null && held.get(i) != null ? "HELD" : "AVAILABLE";
            result.add(new SeatState(seat, status));
        }
        return result;
    }

    public String reservationForToken(String sid, String token) {
        return redis.opsForValue().get(SeatRedisKeys.tokenReservation(sid, token));
    }

    public boolean cancelledForToken(String sid, String token) {
        return Boolean.TRUE.equals(redis.hasKey(SeatRedisKeys.tokenCancelled(sid, token)));
    }

    public String screeningForPayment(String paymentId) {
        return redis.opsForValue().get(SeatRedisKeys.paymentScreening(paymentId));
    }

    public record ConfirmationResult(String code, String reservationId, String status) {
    }

    public ConfirmationResult confirm(String sid, String paymentId, int amount, String transactionId) {
        String raw = redis.execute(stringScript("lua/confirm-v4.lua"), List.of(SeatRedisKeys.payment(sid, paymentId)),
                sid, paymentId, String.valueOf(amount), transactionId, UUID.randomUUID().toString());
        return parseResult(raw);
    }

    public ConfirmationResult cancel(String sid, String paymentId) {
        String raw = redis.execute(stringScript("lua/cancel-v3.lua"), List.of(SeatRedisKeys.payment(sid, paymentId)),
                sid, paymentId);
        return parseResult(raw);
    }

    private ConfirmationResult parseResult(String raw) {
        if (raw == null) throw new IllegalStateException("No Redis confirmation result");
        try {
            JsonNode result = mapper.readTree(raw);
            return new ConfirmationResult(result.path("code").asText(null),
                    result.path("reservationId").asText(null), result.path("status").asText(null));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid Redis confirmation result", exception);
        }
    }

    public void seedReserved(String sid, List<String> seats) {
        if (!seats.isEmpty()) redis.opsForSet().add(SeatRedisKeys.reserved(sid), seats.toArray(String[]::new));
    }

    public void resetReserved(String sid, List<String> seats) {
        redis.delete(SeatRedisKeys.reserved(sid));
        seedReserved(sid, seats);
    }

    public long reservedCount(String sid) {
        Long count = redis.opsForSet().size(SeatRedisKeys.reserved(sid));
        return count == null ? 0 : count;
    }

    public void seedCompleted(String sid, String token, String paymentId, String reservationId) {
        redis.opsForValue().setIfAbsent(SeatRedisKeys.tokenReservation(sid, token), reservationId);
        redis.opsForValue().setIfAbsent(SeatRedisKeys.payment(sid, paymentId),
                "{\"status\":\"CONFIRMED\",\"reservationId\":\"" + reservationId + "\"}");
        redis.opsForValue().setIfAbsent(SeatRedisKeys.paymentScreening(paymentId), sid);
    }

    public String runId() {
        return redis.execute((RedisCallback<String>) connection ->
                connection.serverCommands().info("server").getProperty("run_id"));
    }

    public record RecoveryIntent(String screeningId, String holdId, String paymentId, String queueToken,
                                 String idempotencyKey, List<String> seats, int amount,
                                 String transactionId, String reservationId) {
    }

    public ConfirmationResult recover(RecoveryIntent intent) {
        try {
            String raw = redis.execute(stringScript("lua/recover-v6.lua"),
                    List.of(SeatRedisKeys.payment(intent.screeningId(), intent.paymentId())),
                    intent.screeningId(), intent.paymentId(), intent.holdId(), intent.queueToken(),
                    intent.idempotencyKey(), String.valueOf(intent.amount()), intent.transactionId(),
                    intent.reservationId(), mapper.writeValueAsString(intent.seats()));
            return parseResult(raw);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid recovery seats", exception);
        }
    }

    public RecoveryIntent findStreamEvent(String screeningId, String paymentId) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                .range(SeatRedisKeys.stream(screeningId), Range.unbounded());
        if (records == null) return null;
        for (MapRecord<String, Object, Object> record : records) {
            Object raw = record.getValue().get("event");
            if (raw == null) continue;
            try {
                JsonNode event = mapper.readTree(String.valueOf(raw));
                if (!paymentId.equals(event.path("paymentId").asText())) continue;
                List<String> seats = new ArrayList<>();
                event.path("seats").forEach(seat -> seats.add(seat.asText()));
                return new RecoveryIntent(screeningId, event.path("holdId").asText(), paymentId,
                        event.path("queueToken").asText(), event.path("idempotencyKey").asText(),
                        seats, event.path("amount").asInt(), event.path("transactionId").asText(),
                        event.path("reservationId").asText());
            } catch (JsonProcessingException invalidEvent) {
                continue;
            }
        }
        return null;
    }
}
