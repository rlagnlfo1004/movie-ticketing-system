package com.movie.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.movie.waiting.config.QueueAdmissionProperties;
import com.movie.waiting.config.QueueRegistrationProperties;
import com.movie.waiting.domain.AdmissionRunResult;
import com.movie.waiting.domain.AdmissionRunResult.SkipReason;
import com.movie.waiting.repository.InMemoryActiveAdmissionStore;
import com.movie.waiting.repository.InMemoryAdmissionLockStore;
import com.movie.waiting.repository.InMemoryQueueStore;
import com.movie.waiting.repository.RedisActiveAdmissionRepository;
import com.movie.waiting.repository.RedisAdmissionLockRepository;
import com.movie.waiting.repository.RedisQueueRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueAdmissionServiceTest {

    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneOffset.UTC);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private QueueAdmissionProperties properties;
    private RedisQueueRepository queueRepository;
    private RedisActiveAdmissionRepository activeRepository;
    private InMemoryAdmissionLockStore lockStore;
    private QueueAdmissionService service;

    @BeforeEach
    void setUp() {
        properties = new QueueAdmissionProperties();
        properties.setBatchSize(10);
        properties.setMaxActiveUsers(100);
        properties.setActiveTtl(Duration.ofMinutes(5));
        properties.setLockTtl(Duration.ofSeconds(5));

        queueRepository = new RedisQueueRepository("waiting:screening", new InMemoryQueueStore());
        activeRepository = new RedisActiveAdmissionRepository(
                "waiting:screening",
                new InMemoryActiveAdmissionStore(),
                clock
        );
        lockStore = new InMemoryAdmissionLockStore();
        service = new QueueAdmissionService(
                properties,
                registry("1"),
                queueRepository,
                activeRepository,
                new RedisAdmissionLockRepository("waiting:screening", lockStore),
                clock,
                "instance-a",
                meterRegistry
        );
    }

    @Test
    void admitsExactlyBatchSizeUsersFromOneHundredWaitingEntries() {
        seedWaitingUsers(100);

        AdmissionRunResult result = service.admit("1");

        assertThat(result.successCount()).isEqualTo(10);
        assertThat(result.failureCount()).isZero();
        assertThat(result.skipReason()).isNull();
        assertThat(queueRepository.cardinality("1")).isEqualTo(90L);
        assertThat(activeRepository.countActive("1")).isEqualTo(10L);
    }

    @Test
    void lowerQueueScoresBecomeActiveFirst() {
        queueRepository.addWithScore("1", "third", 3000L);
        queueRepository.addWithScore("1", "first", 1000L);
        queueRepository.addWithScore("1", "second", 2000L);

        service.admit("1");

        assertThat(activeRepository.exists("1", "first")).isTrue();
        assertThat(activeRepository.exists("1", "second")).isTrue();
        assertThat(activeRepository.exists("1", "third")).isTrue();
    }

    @Test
    void emptyQueueProducesZeroAdmissionsAndQueueEmptyResult() {
        AdmissionRunResult result = service.admit("1");

        assertThat(result.successCount()).isZero();
        assertThat(result.skipReason()).isEqualTo(SkipReason.QUEUE_EMPTY);
        assertThat(activeRepository.countActive("1")).isZero();
    }

    @Test
    void maxActiveUsersReachedProducesZeroAdmissions() {
        properties.setMaxActiveUsers(1);
        activeRepository.activate("1", "already-active", Duration.ofMinutes(5));
        queueRepository.addWithScore("1", "waiting", 1000L);

        AdmissionRunResult result = service.admit("1");

        assertThat(result.successCount()).isZero();
        assertThat(result.skipReason()).isEqualTo(SkipReason.ACTIVE_CAPACITY_REACHED);
        assertThat(queueRepository.cardinality("1")).isEqualTo(1L);
    }

    @Test
    void remainingCapacitySmallerThanBatchSizeAdmitsOnlyRemainingCapacity() {
        properties.setMaxActiveUsers(3);
        activeRepository.activate("1", "active-a", Duration.ofMinutes(5));
        seedWaitingUsers(10);

        AdmissionRunResult result = service.admit("1");

        assertThat(result.successCount()).isEqualTo(2);
        assertThat(activeRepository.countActive("1")).isEqualTo(3L);
        assertThat(queueRepository.cardinality("1")).isEqualTo(8L);
    }

    @Test
    void lockMissDoesNotMutateQueueOrActiveState() {
        queueRepository.addWithScore("1", "token-a", 1000L);
        lockStore.hold("waiting:screening:1:admission:lock", "other-owner");

        AdmissionRunResult result = service.admit("1");

        assertThat(result.lockAcquired()).isFalse();
        assertThat(result.skipReason()).isEqualTo(SkipReason.LOCK_NOT_ACQUIRED);
        assertThat(queueRepository.cardinality("1")).isEqualTo(1L);
        assertThat(activeRepository.countActive("1")).isZero();
    }

    @Test
    void recordsMetricsForRunsSuccessesLockMissesAndCapacityHits() {
        queueRepository.addWithScore("1", "token-a", 1000L);
        service.admit("1");

        lockStore.hold("waiting:screening:1:admission:lock", "other-owner");
        service.admit("1");
        lockStore.release("waiting:screening:1:admission:lock", "other-owner");

        properties.setMaxActiveUsers(1);
        queueRepository.addWithScore("1", "token-b", 2000L);
        service.admit("1");

        assertThat(counter("waiting.admission.runs")).isEqualTo(3.0);
        assertThat(counter("waiting.admission.success")).isEqualTo(1.0);
        assertThat(counter("waiting.admission.lock.miss")).isEqualTo(1.0);
        assertThat(counter("waiting.admission.capacity.hit")).isEqualTo(1.0);
    }

    private void seedWaitingUsers(int count) {
        for (int i = 1; i <= count; i++) {
            queueRepository.addWithScore("1", "token-%03d".formatted(i), i);
        }
    }

    private double counter(String name) {
        return meterRegistry.find(name).counter().count();
    }

    private ScreeningRegistry registry(String... screeningIds) {
        QueueRegistrationProperties registrationProperties = new QueueRegistrationProperties();
        registrationProperties.setValidScreeningIds(Set.of(screeningIds));
        return new ScreeningRegistry(registrationProperties);
    }
}
