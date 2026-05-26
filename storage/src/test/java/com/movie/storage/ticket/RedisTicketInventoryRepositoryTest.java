package com.movie.storage.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class RedisTicketInventoryRepositoryTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RedisTicketInventoryRepository repository;
    private String screeningId;

    @BeforeAll
    static void startRedisConnection() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        assertRedisAvailable();
    }

    @AfterAll
    static void closeRedisConnection() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        repository = new RedisTicketInventoryRepository(redisTemplate);
        ReflectionTestUtils.setField(repository, "keyPrefix", "ticket");
        ReflectionTestUtils.setField(repository, "issuedTokenTtl", Duration.ofMinutes(30));
        screeningId = "test-" + UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        deleteInventoryKey(screeningId);
        deleteIssuedTokenKeys(screeningId);
    }

    @Test
    void initializesAndReadsInventory() {
        repository.initialize(screeningId, 7);

        assertThat(repository.remaining(screeningId)).isEqualTo(7);
    }

    @Test
    void initializesMissingInventoryOnlyOnce() {
        assertThat(repository.initializeIfAbsent(screeningId, 7)).isTrue();
        assertThat(repository.initializeIfAbsent(screeningId, 9)).isFalse();

        assertThat(repository.remaining(screeningId)).isEqualTo(7);
    }

    @Test
    void stockTenDeductionIssuesOneTicketAndLeavesNineRemaining() {
        repository.initialize(screeningId, 10);

        TicketInventoryResult result = repository.decrementOne(screeningId, uniqueQueueToken());

        assertThat(result).isEqualTo(TicketInventoryResult.ISSUED);
        assertThat(repository.remaining(screeningId)).isEqualTo(9);
    }

    @Test
    void stockZeroDeductionFailsAndLeavesStockUnchanged() {
        repository.initialize(screeningId, 0);

        TicketInventoryResult result = repository.decrementOne(screeningId, uniqueQueueToken());

        assertThat(result).isEqualTo(TicketInventoryResult.SOLD_OUT);
        assertThat(repository.remaining(screeningId)).isZero();
    }

    @Test
    void missingInventoryDeductionFailsWithoutCreatingNegativeStock() {
        TicketInventoryResult result = repository.decrementOne(screeningId, uniqueQueueToken());

        assertThat(result).isEqualTo(TicketInventoryResult.SOLD_OUT);
        assertThat(redisTemplate.hasKey(inventoryKey(screeningId))).isFalse();
    }

    @Test
    void negativeInventoryDeductionFailsAndPreservesNegativeValue() {
        redisTemplate.opsForValue().set(inventoryKey(screeningId), "-3");

        TicketInventoryResult result = repository.decrementOne(screeningId, uniqueQueueToken());

        assertThat(result).isEqualTo(TicketInventoryResult.SOLD_OUT);
        assertThat(redisTemplate.opsForValue().get(inventoryKey(screeningId))).isEqualTo("-3");
    }

    @Test
    void nonNumericInventoryDeductionFailsAndPreservesInvalidValue() {
        redisTemplate.opsForValue().set(inventoryKey(screeningId), "not-a-number");

        TicketInventoryResult result = repository.decrementOne(screeningId, uniqueQueueToken());

        assertThat(result).isEqualTo(TicketInventoryResult.SOLD_OUT);
        assertThat(redisTemplate.opsForValue().get(inventoryKey(screeningId))).isEqualTo("not-a-number");
    }

    @Test
    void duplicateQueueTokenFailsAndLeavesStockUnchanged() {
        repository.initialize(screeningId, 10);
        String queueToken = uniqueQueueToken();

        assertThat(repository.decrementOne(screeningId, queueToken)).isEqualTo(TicketInventoryResult.ISSUED);
        TicketInventoryResult duplicateResult = repository.decrementOne(screeningId, queueToken);

        assertThat(duplicateResult).isEqualTo(TicketInventoryResult.DUPLICATE_TOKEN);
        assertThat(repository.remaining(screeningId)).isEqualTo(9);
    }

    @Test
    void concurrentDeductionNeverIssuesMoreThanInitialStock() throws Exception {
        repository.initialize(screeningId, 100);
        int attempts = 1_000;
        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<TicketInventoryResult>> calls = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            calls.add(() -> {
                start.await();
                return repository.decrementOne(screeningId, uniqueQueueToken());
            });
        }

        List<Future<TicketInventoryResult>> futures = calls.stream()
                .map(executor::submit)
                .toList();
        start.countDown();
        executor.shutdown();

        assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        Map<TicketInventoryResult, Long> resultCounts = futures.stream()
                .map(this::getResult)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(resultCounts.getOrDefault(TicketInventoryResult.ISSUED, 0L)).isEqualTo(100);
        assertThat(resultCounts.getOrDefault(TicketInventoryResult.SOLD_OUT, 0L)).isEqualTo(900);
        assertThat(resultCounts.getOrDefault(TicketInventoryResult.DUPLICATE_TOKEN, 0L)).isZero();
        assertThat(repository.remaining(screeningId)).isZero();
    }

    private TicketInventoryResult getResult(Future<TicketInventoryResult> future) {
        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent deduction did not complete", exception);
        }
    }

    private String uniqueQueueToken() {
        return "queue-token-" + UUID.randomUUID();
    }

    private String inventoryKey(String screeningId) {
        return "ticket:" + screeningId + ":inventory";
    }

    private String issuedTokenPattern(String screeningId) {
        return "ticket:" + screeningId + ":issued-token:*";
    }

    private void deleteInventoryKey(String screeningId) {
        redisTemplate.delete(inventoryKey(screeningId));
    }

    private void deleteIssuedTokenKeys(String screeningId) {
        redisTemplate.delete(redisTemplate.keys(issuedTokenPattern(screeningId)));
    }

    private static void assertRedisAvailable() {
        try {
            assertThat(redisTemplate.getConnectionFactory()).isNotNull();
            redisTemplate.getConnectionFactory().getConnection().ping();
        } catch (RedisConnectionException exception) {
            throw new IllegalStateException("Redis must be available on localhost:6379. Run `docker compose up -d redis`.", exception);
        }
    }
}
