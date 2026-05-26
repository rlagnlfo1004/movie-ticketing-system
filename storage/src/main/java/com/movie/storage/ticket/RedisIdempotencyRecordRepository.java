package com.movie.storage.ticket;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisIdempotencyRecordRepository implements IdempotencyRecordRepository {

    private static final String KEY_PREFIX = "idempotency";

    private final StringRedisTemplate redisTemplate;
    private final Clock clock = Clock.systemUTC();

    @Override
    public IdempotencyClaimResult claim(String screeningId, String idempotencyKey, String fingerprint, Duration ttl) {
        String key = key(screeningId, idempotencyKey);
        String value = IdempotencyRecord.inProgress(fingerprint, clock.instant()).serialize();
        try {
            Boolean claimed = redisTemplate.opsForValue().setIfAbsent(key, value, ttl);
            if (Boolean.TRUE.equals(claimed)) {
                return IdempotencyClaimResult.CLAIMED;
            }
            String existingValue = redisTemplate.opsForValue().get(key);
            if (existingValue == null) {
                return IdempotencyClaimResult.STORE_UNAVAILABLE;
            }
            IdempotencyRecord existing = IdempotencyRecord.deserialize(existingValue);
            if (Objects.equals(existing.fingerprint(), fingerprint)) {
                return IdempotencyClaimResult.DUPLICATE_IN_PROGRESS;
            }
            return IdempotencyClaimResult.FINGERPRINT_CONFLICT;
        } catch (IllegalArgumentException | RedisConnectionFailureException | RedisSystemException exception) {
            return IdempotencyClaimResult.STORE_UNAVAILABLE;
        } catch (RuntimeException exception) {
            return IdempotencyClaimResult.STORE_UNAVAILABLE;
        }
    }

    private String key(String screeningId, String idempotencyKey) {
        return KEY_PREFIX + ":" + screeningId + ":" + idempotencyKey;
    }
}
