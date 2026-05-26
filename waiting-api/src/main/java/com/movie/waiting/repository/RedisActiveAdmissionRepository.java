package com.movie.waiting.repository;

import com.movie.waiting.config.QueueRegistrationProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisActiveAdmissionRepository {

    private final String redisKeyPrefix;
    private final ActiveAdmissionStore store;
    private final Clock clock;

    @Autowired
    public RedisActiveAdmissionRepository(QueueRegistrationProperties properties, StringRedisTemplate redisTemplate) {
        this(properties.getRedisKeyPrefix(), new RedisTemplateActiveAdmissionStore(redisTemplate), Clock.systemUTC());
    }

    public RedisActiveAdmissionRepository(String redisKeyPrefix, ActiveAdmissionStore store, Clock clock) {
        this.redisKeyPrefix = redisKeyPrefix;
        this.store = store;
        this.clock = clock;
    }

    public boolean activate(String screeningId, String token, Duration ttl) {
        long expiresAtMillis = clock.millis() + ttl.toMillis();
        String activeKey = activeKey(screeningId, token);
        boolean created = store.setActiveIfAbsent(activeKey, token, ttl);
        if (created) {
            store.addToActiveIndex(activeIndexKey(screeningId), token, expiresAtMillis);
        }
        return created;
    }

    public boolean exists(String screeningId, String token) {
        return store.exists(activeKey(screeningId, token));
    }

    public long countActive(String screeningId) {
        cleanupExpired(screeningId);
        Long count = store.indexCardinality(activeIndexKey(screeningId));
        return count == null ? 0L : count;
    }

    public void cleanupExpired(String screeningId) {
        String indexKey = activeIndexKey(screeningId);
        Collection<String> candidates = store.rangeByExpiresAt(indexKey, 0, clock.millis());
        for (String token : candidates) {
            if (!exists(screeningId, token)) {
                store.removeFromActiveIndex(indexKey, token);
            }
        }
    }

    public String activeKey(String screeningId, String token) {
        return redisKeyPrefix + ":" + screeningId + ":active:" + token;
    }

    public String activeIndexKey(String screeningId) {
        return redisKeyPrefix + ":" + screeningId + ":active:index";
    }

    public interface ActiveAdmissionStore {
        boolean setActiveIfAbsent(String key, String token, Duration ttl);

        boolean exists(String key);

        void addToActiveIndex(String indexKey, String token, double expiresAtMillis);

        Long indexCardinality(String indexKey);

        Collection<String> rangeByExpiresAt(String indexKey, double min, double max);

        void removeFromActiveIndex(String indexKey, String token);
    }

    private record RedisTemplateActiveAdmissionStore(StringRedisTemplate redisTemplate) implements ActiveAdmissionStore {

        @Override
        public boolean setActiveIfAbsent(String key, String token, Duration ttl) {
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, token, ttl));
        }

        @Override
        public boolean exists(String key) {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        }

        @Override
        public void addToActiveIndex(String indexKey, String token, double expiresAtMillis) {
            redisTemplate.opsForZSet().add(indexKey, token, expiresAtMillis);
        }

        @Override
        public Long indexCardinality(String indexKey) {
            return redisTemplate.opsForZSet().zCard(indexKey);
        }

        @Override
        public Collection<String> rangeByExpiresAt(String indexKey, double min, double max) {
            Set<String> tokens = redisTemplate.opsForZSet().rangeByScore(indexKey, min, max);
            return tokens == null ? List.of() : tokens;
        }

        @Override
        public void removeFromActiveIndex(String indexKey, String token) {
            redisTemplate.opsForZSet().remove(indexKey, token);
        }
    }
}
