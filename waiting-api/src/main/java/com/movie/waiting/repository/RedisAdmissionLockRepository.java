package com.movie.waiting.repository;

import com.movie.waiting.config.QueueRegistrationProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisAdmissionLockRepository {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final String redisKeyPrefix;
    private final AdmissionLockStore store;

    @Autowired
    public RedisAdmissionLockRepository(QueueRegistrationProperties properties, StringRedisTemplate redisTemplate) {
        this(properties.getRedisKeyPrefix(), new RedisTemplateAdmissionLockStore(redisTemplate));
    }

    public RedisAdmissionLockRepository(String redisKeyPrefix, AdmissionLockStore store) {
        this.redisKeyPrefix = redisKeyPrefix;
        this.store = store;
    }

    public boolean acquire(String screeningId, String ownerId, Duration ttl) {
        return store.acquire(lockKey(screeningId), ownerId, ttl);
    }

    public boolean release(String screeningId, String ownerId) {
        return store.release(lockKey(screeningId), ownerId);
    }

    public String lockKey(String screeningId) {
        return redisKeyPrefix + ":" + screeningId + ":admission:lock";
    }

    public interface AdmissionLockStore {
        boolean acquire(String key, String ownerId, Duration ttl);

        boolean release(String key, String ownerId);
    }

    private record RedisTemplateAdmissionLockStore(StringRedisTemplate redisTemplate) implements AdmissionLockStore {

        @Override
        public boolean acquire(String key, String ownerId, Duration ttl) {
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, ownerId, ttl));
        }

        @Override
        public boolean release(String key, String ownerId) {
            Long removed = redisTemplate.execute(RELEASE_SCRIPT, List.of(key), ownerId);
            return Long.valueOf(1L).equals(removed);
        }
    }
}
