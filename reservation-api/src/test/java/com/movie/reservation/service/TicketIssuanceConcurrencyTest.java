package com.movie.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.movie.storage.ticket.IdempotencyClaimResult;
import com.movie.storage.ticket.IdempotencyRecordRepository;
import com.movie.storage.ticket.TicketInventoryResult;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TicketIssuanceConcurrencyTest {

    @Test
    void inMemoryInventoryModelNeverIssuesMoreThanAvailableStock() throws Exception {
        AtomicInteger stock = new AtomicInteger(3);
        Set<String> issuedTokens = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < 10; i++) {
            int index = i;
            executor.submit(() -> {
                start.await();
                if (decrementOne(stock, issuedTokens, "token-" + index) == TicketInventoryResult.ISSUED) {
                    successes.incrementAndGet();
                }
                return null;
            });
        }

        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(successes.get()).isLessThanOrEqualTo(3);
        assertThat(stock.get()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void sameIdempotencyKeyAllowsOnlyOneConcurrentClaim() throws Exception {
        InMemoryIdempotencyRepository repository = new InMemoryIdempotencyRepository();
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                start.await();
                IdempotencyClaimResult result = repository.claim("1", "idem-key-123", "fingerprint-a", Duration.ofMinutes(10));
                if (result == IdempotencyClaimResult.CLAIMED) {
                    claimed.incrementAndGet();
                }
                if (result == IdempotencyClaimResult.DUPLICATE_IN_PROGRESS) {
                    duplicates.incrementAndGet();
                }
                return null;
            });
        }

        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(claimed.get()).isEqualTo(1);
        assertThat(duplicates.get()).isEqualTo(99);
    }

    private TicketInventoryResult decrementOne(AtomicInteger stock, Set<String> issuedTokens, String queueToken) {
        synchronized (stock) {
            if (!issuedTokens.add(queueToken)) {
                return TicketInventoryResult.DUPLICATE_TOKEN;
            }
            if (stock.get() <= 0) {
                issuedTokens.remove(queueToken);
                return TicketInventoryResult.SOLD_OUT;
            }
            stock.decrementAndGet();
            return TicketInventoryResult.ISSUED;
        }
    }

    private static class InMemoryIdempotencyRepository implements IdempotencyRecordRepository {
        private final Set<String> keys = ConcurrentHashMap.newKeySet();

        @Override
        public IdempotencyClaimResult claim(String screeningId, String idempotencyKey, String fingerprint, Duration ttl) {
            if (keys.add(screeningId + ":" + idempotencyKey)) {
                return IdempotencyClaimResult.CLAIMED;
            }
            return IdempotencyClaimResult.DUPLICATE_IN_PROGRESS;
        }
    }
}
