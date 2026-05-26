package com.movie.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.movie.waiting.config.QueueRegistrationProperties;
import com.movie.waiting.domain.QueueEntry;
import com.movie.waiting.repository.RedisQueueRepository;
import com.movie.waiting.repository.RedisQueueRepository.QueueStore;
import com.movie.waiting.repository.RedisQueueRepository.ScoredMember;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class QueueRegistrationConcurrencyTest {

    @Test
    void registersOneThousandConcurrentGeneratedTokensWithoutStorageFailures() throws Exception {
        ConcurrentQueueStore store = new ConcurrentQueueStore();
        RedisQueueRepository repository = new RedisQueueRepository("waiting:screening", store);
        QueueRegistrationService service = new QueueRegistrationService(registry(), repository);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(1000);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> tokens = ConcurrentHashMap.newKeySet();
        AtomicLong failures = new AtomicLong();

        for (int i = 0; i < 1000; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    QueueEntry entry = service.register("1", null);
                    tokens.add(entry.token());
                } catch (Exception exception) {
                    failures.incrementAndGet();
                }
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).hasValue(0L);
        assertThat(tokens).hasSize(1000);
        assertThat(repository.cardinality("1")).isEqualTo(1000L);
    }

    private ScreeningRegistry registry() {
        QueueRegistrationProperties properties = new QueueRegistrationProperties();
        properties.setValidScreeningIds(Set.of("1"));
        return new ScreeningRegistry(properties);
    }

    private static final class ConcurrentQueueStore implements QueueStore {

        private final Map<String, Map<String, Double>> queues = new ConcurrentHashMap<>();

        @Override
        public boolean addIfAbsent(String key, String token, double score) {
            return queues.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>())
                    .putIfAbsent(token, score) == null;
        }

        @Override
        public Double score(String key, String token) {
            return queues.getOrDefault(key, Map.of()).get(token);
        }

        @Override
        public Long rank(String key, String token) {
            int index = queues.getOrDefault(key, Map.of()).entrySet().stream()
                    .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                    .map(Map.Entry::getKey)
                    .toList()
                    .indexOf(token);
            return index < 0 ? null : (long) index;
        }

        @Override
        public Long cardinality(String key) {
            return (long) queues.getOrDefault(key, Map.of()).size();
        }

        @Override
        public Collection<ScoredMember> popMin(String key, long count) {
            Map<String, Double> queue = queues.getOrDefault(key, Map.of());
            return queue.entrySet().stream()
                    .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                    .limit(count)
                    .map(entry -> new ScoredMember(entry.getKey(), entry.getValue()))
                    .toList();
        }
    }
}
