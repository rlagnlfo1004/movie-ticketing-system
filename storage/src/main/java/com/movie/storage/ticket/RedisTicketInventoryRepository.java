package com.movie.storage.ticket;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisTicketInventoryRepository implements TicketInventoryRepository {

    private static final DefaultRedisScript<Long> DECREMENT_SCRIPT = new DefaultRedisScript<>("""
            local inventory = KEYS[1]
            local issued = KEYS[2]
            local ttlMillis = tonumber(ARGV[1])
            if redis.call('EXISTS', issued) == 1 then
              return 1
            end
            local current = redis.call('GET', inventory)
            if current == false then
              return 2
            end
            local remaining = tonumber(current)
            if remaining == nil or remaining <= 0 then
              return 2
            end
            redis.call('DECR', inventory)
            redis.call('SET', issued, '1', 'PX', ttlMillis)
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    @Value("${ticket.inventory.key-prefix:ticket}")
    private String keyPrefix;

    @Value("${ticket.inventory.issued-token-ttl:PT30M}")
    private Duration issuedTokenTtl;

    @Override
    public void initialize(String screeningId, int quantity) {
        redisTemplate.opsForValue().set(inventoryKey(screeningId), Integer.toString(quantity));
    }

    @Override
    public boolean initializeIfAbsent(String screeningId, int quantity) {
        Boolean initialized = redisTemplate.opsForValue().setIfAbsent(inventoryKey(screeningId), Integer.toString(quantity));
        return Boolean.TRUE.equals(initialized);
    }

    @Override
    public TicketInventoryResult decrementOne(String screeningId, String queueToken) {
        Long result = redisTemplate.execute(
                DECREMENT_SCRIPT,
                List.of(inventoryKey(screeningId), issuedTokenKey(screeningId, queueToken)),
                Long.toString(issuedTokenTtl.toMillis())
        );
        return switch (result == null ? 2 : result.intValue()) {
            case 0 -> TicketInventoryResult.ISSUED;
            case 1 -> TicketInventoryResult.DUPLICATE_TOKEN;
            default -> TicketInventoryResult.SOLD_OUT;
        };
    }

    @Override
    public long remaining(String screeningId) {
        String value = redisTemplate.opsForValue().get(inventoryKey(screeningId));
        if (value == null) {
            return 0;
        }
        return Long.parseLong(value);
    }

    private String inventoryKey(String screeningId) {
        return keyPrefix + ":" + screeningId + ":inventory";
    }

    private String issuedTokenKey(String screeningId, String queueToken) {
        return keyPrefix + ":" + screeningId + ":issued-token:" + queueToken;
    }
}
