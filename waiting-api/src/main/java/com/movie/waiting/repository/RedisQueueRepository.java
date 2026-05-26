package com.movie.waiting.repository;

import com.movie.waiting.config.QueueRegistrationProperties;
import com.movie.waiting.domain.QueueEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Repository;

@Repository
public class RedisQueueRepository {

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

    public String queueKey(String screeningId) {
        return redisKeyPrefix + ":" + screeningId + ":queue";
    }

    public interface QueueStore {
        boolean addIfAbsent(String key, String token, double score);

        Double score(String key, String token);

        Long rank(String key, String token);

        Long cardinality(String key);

        Collection<ScoredMember> popMin(String key, long count);
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
    }
}
