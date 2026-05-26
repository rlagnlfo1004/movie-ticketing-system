package com.movie.storage.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisIdempotencyRecordRepositoryTest {

    @Mock
    StringRedisTemplate redisTemplate;

    @Mock
    ValueOperations<String, String> valueOperations;

    RedisIdempotencyRecordRepository repository;

    @BeforeEach
    void setUp() {
        repository = new RedisIdempotencyRecordRepository(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void claimedWhenSetIfAbsentWins() {
        when(valueOperations.setIfAbsent(eq("idempotency:1:idem-key-123"), any(String.class), eq(Duration.ofMinutes(10))))
                .thenReturn(true);

        IdempotencyClaimResult result = repository.claim("1", "idem-key-123", "fingerprint-a", Duration.ofMinutes(10));

        assertThat(result).isEqualTo(IdempotencyClaimResult.CLAIMED);
    }

    @Test
    void duplicateInProgressWhenExistingFingerprintMatches() {
        when(valueOperations.setIfAbsent(eq("idempotency:1:idem-key-123"), any(String.class), eq(Duration.ofMinutes(10))))
                .thenReturn(false);
        when(valueOperations.get("idempotency:1:idem-key-123"))
                .thenReturn(IdempotencyRecord.inProgress("fingerprint-a", java.time.Instant.parse("2026-05-12T00:00:00Z")).serialize());

        IdempotencyClaimResult result = repository.claim("1", "idem-key-123", "fingerprint-a", Duration.ofMinutes(10));

        assertThat(result).isEqualTo(IdempotencyClaimResult.DUPLICATE_IN_PROGRESS);
    }

    @Test
    void fingerprintConflictWhenExistingFingerprintDiffers() {
        when(valueOperations.setIfAbsent(eq("idempotency:1:idem-key-123"), any(String.class), eq(Duration.ofMinutes(10))))
                .thenReturn(false);
        when(valueOperations.get("idempotency:1:idem-key-123"))
                .thenReturn(IdempotencyRecord.inProgress("fingerprint-a", java.time.Instant.parse("2026-05-12T00:00:00Z")).serialize());

        IdempotencyClaimResult result = repository.claim("1", "idem-key-123", "fingerprint-b", Duration.ofMinutes(10));

        assertThat(result).isEqualTo(IdempotencyClaimResult.FINGERPRINT_CONFLICT);
    }

    @Test
    void storeUnavailableWhenRedisFailsClosed() {
        when(valueOperations.setIfAbsent(eq("idempotency:1:idem-key-123"), any(String.class), eq(Duration.ofMinutes(10))))
                .thenThrow(new RuntimeException("redis unavailable"));

        IdempotencyClaimResult result = repository.claim("1", "idem-key-123", "fingerprint-a", Duration.ofMinutes(10));

        assertThat(result).isEqualTo(IdempotencyClaimResult.STORE_UNAVAILABLE);
    }
}
