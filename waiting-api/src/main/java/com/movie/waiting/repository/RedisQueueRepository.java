package com.movie.waiting.repository;

import com.movie.waiting.config.QueueRegistrationProperties;
import com.movie.waiting.domain.QueueEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisQueueRepository {

    private static final DefaultRedisScript<Long> ADMIT_FENCED_SCRIPT = new DefaultRedisScript<>();

    static {
        ADMIT_FENCED_SCRIPT.setLocation(new ClassPathResource("lua/admit-v7.lua"));
        ADMIT_FENCED_SCRIPT.setResultType(Long.class);
    }

    private final String redisKeyPrefix;
    private final QueueStore queueStore;

    @Autowired
    public RedisQueueRepository(QueueRegistrationProperties properties, StringRedisTemplate redisTemplate) {
        this(properties.getRedisKeyPrefix(), new RedisTemplateQueueStore(redisTemplate));
    }

    public RedisQueueRepository(String redisKeyPrefix, QueueStore queueStore) {
        this.redisKeyPrefix = redisKeyPrefix;
        this.queueStore = queueStore;
    }

    public QueueEntry register(String screeningId, String token, long score) {
        String key = queueKey(screeningId);
        boolean inserted = queueStore.addIfAbsent(key, token, score);
        Double storedScore = queueStore.score(key, token);
        long effectiveScore = storedScore == null ? score : storedScore.longValue();

        return new QueueEntry(screeningId, token, effectiveScore, true, inserted);
    }

    public QueueEntry addWithScore(String screeningId, String token, long score) {
        return register(screeningId, token, score);
    }

    public Long cardinality(String screeningId) {
        return queueStore.cardinality(queueKey(screeningId));
    }

    public Long rank(String screeningId, String token) {
        return queueStore.rank(queueKey(screeningId), token);
    }

    public Double score(String screeningId, String token) {
        return queueStore.score(queueKey(screeningId), token);
    }

    public List<QueueEntry> popEarliest(String screeningId, long count) {
        String key = queueKey(screeningId);
        return queueStore.popMin(key, count).stream()
                .map(member -> new QueueEntry(screeningId, member.value(), member.score().longValue(), true, false))
                .toList();
    }

    public long admitFenced(String screeningId, String lockOwner, long epoch, long nowMillis,
                            int maxActive, int batchSize, Duration activeTtl) {
        Long result = queueStore.admitFenced(queueKey(screeningId), lockKey(screeningId),
                activeIndexKey(screeningId), fenceKey(screeningId), lockOwner, epoch, nowMillis,
                maxActive, batchSize, activeTtl.toMillis(), activeKeyPrefix(screeningId));
        if (result == null) throw new IllegalStateException("No fenced admission result");
        return result;
    }

    public String queueKey(String screeningId) {
        return redisKeyPrefix + ":" + screeningId + ":queue";
    }

    private String lockKey(String screeningId) {
        return redisKeyPrefix + ":" + screeningId + ":admission:lock";
    }

    private String activeIndexKey(String screeningId) {
        return redisKeyPrefix + ":" + screeningId + ":active:index";
    }

    private String activeKeyPrefix(String screeningId) {
        return redisKeyPrefix + ":" + screeningId + ":active:";
    }

    private String fenceKey(String screeningId) {
        return redisKeyPrefix + ":" + screeningId + ":admission:fence";
    }

    public interface QueueStore {
        boolean addIfAbsent(String key, String token, double score);

        Double score(String key, String token);

        Long rank(String key, String token);

        Long cardinality(String key);

        Collection<ScoredMember> popMin(String key, long count);

        default Long admitFenced(String queue, String lock, String activeIndex, String fence,
                                 String lockOwner, long epoch, long nowMillis, int maxActive,
                                 int batchSize, long activeTtlMillis, String activeKeyPrefix) {
            throw new UnsupportedOperationException("Fenced admission is not configured");
        }
    }

    public record ScoredMember(String value, Double score) {
    }

    @RequiredArgsConstructor
    private static final class RedisTemplateQueueStore implements QueueStore {

        private final StringRedisTemplate redisTemplate;

        @Override
        public boolean addIfAbsent(String key, String token, double score) {
            return Boolean.TRUE.equals(redisTemplate.opsForZSet().addIfAbsent(key, token, score));
        }

        @Override
        public Double score(String key, String token) {
            return redisTemplate.opsForZSet().score(key, token);
        }

        @Override
        public Long rank(String key, String token) {
            return redisTemplate.opsForZSet().rank(key, token);
        }

        @Override
        public Long cardinality(String key) {
            return redisTemplate.opsForZSet().zCard(key);
        }

        @Override
        public Collection<ScoredMember> popMin(String key, long count) {
            Collection<TypedTuple<String>> tuples = redisTemplate.opsForZSet().popMin(key, count);
            if (tuples == null) {
                return List.of();
            }

            List<ScoredMember> members = new ArrayList<>(tuples.size());
            for (TypedTuple<String> tuple : tuples) {
                if (tuple != null && tuple.getValue() != null && tuple.getScore() != null) {
                    members.add(new ScoredMember(tuple.getValue(), tuple.getScore()));
                }
            }
            return members;
        }

        @Override
        public Long admitFenced(String queue, String lock, String activeIndex, String fence,
                                String lockOwner, long epoch, long nowMillis, int maxActive,
                                int batchSize, long activeTtlMillis, String activeKeyPrefix) {
            return redisTemplate.execute(ADMIT_FENCED_SCRIPT, List.of(queue, lock, activeIndex, fence),
                    lockOwner, String.valueOf(epoch), String.valueOf(nowMillis), String.valueOf(maxActive),
                    String.valueOf(batchSize), String.valueOf(activeTtlMillis), activeKeyPrefix);
        }
    }
}
