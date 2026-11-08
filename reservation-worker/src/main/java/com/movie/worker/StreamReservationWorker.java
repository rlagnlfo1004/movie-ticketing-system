package com.movie.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movie.storage.seat.SeatRedisKeys;
import io.lettuce.core.RedisClient;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.models.stream.ClaimedMessages;
import io.lettuce.core.StreamMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StreamReservationWorker {

    private static final String GROUP = "workers";
    private static final String STREAM = SeatRedisKeys.stream("1");
    private static final String DLQ = SeatRedisKeys.deadLetterStream("1");

    private final StringRedisTemplate redis;
    private final ReservationBatchWriter writer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String consumerName = "worker-" + UUID.randomUUID();

    @Value("${worker.test-write-pause:0ms}")
    private Duration testWritePause;

    @Value("${worker.reclaim-min-idle:30s}")
    private Duration reclaimMinIdle;

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    private RedisClient nativeClient;
    private StatefulRedisConnection<String, String> nativeConnection;
    private volatile boolean databaseUnavailable;

    private record PendingEvent(String id, String payload) {
    }

    @PostConstruct
    public void createGroup() {
        try {
            redis.execute((RedisCallback<Object>) connection -> connection.execute("XGROUP",
                    bytes("CREATE"), bytes(STREAM), bytes(GROUP), bytes("0"), bytes("MKSTREAM")));
        } catch (DataAccessException exception) {
            Throwable cause = exception;
            boolean alreadyExists = false;
            while (cause != null) {
                if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) {
                    alreadyExists = true;
                    break;
                }
                cause = cause.getCause();
            }
            if (!alreadyExists) throw exception;
        }
        nativeClient = RedisClient.create("redis://" + redisHost + ":6379");
        nativeConnection = nativeClient.connect();
    }

    @PreDestroy
    public void closeNativeConnection() {
        if (nativeConnection != null) nativeConnection.close();
        if (nativeClient != null) nativeClient.shutdown();
    }

    @Scheduled(fixedDelay = 50)
    public void readNew() {
        if (!databaseReady()) return;
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(GROUP, consumerName), StreamReadOptions.empty().count(100).block(Duration.ofSeconds(1)),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) return;
        List<PendingEvent> batch = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            Object payload = record.getValue().get("event");
            batch.add(new PendingEvent(record.getId().getValue(), decode(payload)));
        }
        process(batch);
    }

    @Scheduled(fixedDelay = 5000)
    public void recoverPending() {
        if (!databaseReady()) return;
        ClaimedMessages<String, String> claimed = nativeConnection.sync().xautoclaim(STREAM,
                XAutoClaimArgs.Builder.xautoclaim(io.lettuce.core.Consumer.from(GROUP, consumerName),
                        reclaimMinIdle, "0-0").count(100));
        List<PendingEvent> batch = new ArrayList<>();
        for (StreamMessage<String, String> message : claimed.getMessages()) {
            String payload = message.getBody().get("event");
            if (payload != null) batch.add(new PendingEvent(message.getId(), payload));
        }
        process(batch);
    }

    private void process(List<PendingEvent> batch) {
        if (batch.isEmpty()) return;
        try {
            List<ReservationEvent> events = new ArrayList<>(batch.size());
            for (PendingEvent message : batch) events.add(mapper.readValue(message.payload(), ReservationEvent.class));
            if (!testWritePause.isZero()) Thread.sleep(testWritePause);
            writer.persist(events);
            for (PendingEvent message : batch) {
                redis.opsForStream().acknowledge(STREAM, GROUP, message.id());
                redis.delete(retryKey(message.id()));
            }
        } catch (Exception exception) {
            databaseUnavailable = true;
            log.error("Stream reservation batch failed", exception);
            for (PendingEvent message : batch) {
                Long attempts = redis.opsForValue().increment(retryKey(message.id()));
                if (attempts != null && attempts >= 4) {
                    redis.opsForStream().add(DLQ, Map.of("event", message.payload(),
                            "reason", exception.getClass().getSimpleName()));
                    redis.opsForStream().acknowledge(STREAM, GROUP, message.id());
                    redis.delete(retryKey(message.id()));
                }
            }
        }
    }

    private String retryKey(String id) {
        return "stream:{1}:retry:" + id;
    }

    private boolean databaseReady() {
        if (!databaseUnavailable) return true;
        if (!writer.databaseAvailable()) return false;
        databaseUnavailable = false;
        return true;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String decode(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return String.valueOf(value);
    }

}
