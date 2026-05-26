package com.movie.waiting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.movie.waiting.config.QueueRegistrationProperties;
import com.movie.waiting.repository.RedisQueueRepository;
import com.movie.waiting.repository.RedisQueueRepository.QueueStore;
import com.movie.waiting.repository.RedisQueueRepository.ScoredMember;
import com.movie.waiting.service.QueueRegistrationService;
import com.movie.waiting.service.ScreeningRegistry;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QueueRegistrationControllerConcurrencyTest {

    @Test
    void acceptsConcurrentRegistrationRequests() throws Exception {
        ConcurrentQueueStore store = new ConcurrentQueueStore();
        RedisQueueRepository repository = new RedisQueueRepository("waiting:screening", store);
        QueueRegistrationService service = new QueueRegistrationService(registry(), repository);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new QueueRegistrationController(service))
                .build();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(1000);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < 1000; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    mockMvc.perform(post("/api/v1/screenings/1/queue/register")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                            .andExpect(status().isOk());
                    successes.incrementAndGet();
                } catch (Exception ignored) {
                    // Counted by the final assertion through the success total.
                }
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        assertThat(successes).hasValue(1000);
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
            return queues.getOrDefault(key, Map.of()).entrySet().stream()
                    .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                    .limit(count)
                    .map(entry -> new ScoredMember(entry.getKey(), entry.getValue()))
                    .toList();
        }
    }
}
