package com.movie.waiting.service;

import com.movie.waiting.config.QueueAdmissionProperties;
import com.movie.waiting.domain.AdmissionBatch;
import com.movie.waiting.domain.AdmissionRunResult;
import com.movie.waiting.domain.AdmissionRunResult.SkipReason;
import com.movie.waiting.domain.QueueEntry;
import com.movie.waiting.repository.RedisActiveAdmissionRepository;
import com.movie.waiting.repository.RedisAdmissionLockRepository;
import com.movie.waiting.repository.RedisQueueRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class QueueAdmissionService {

    private final QueueAdmissionProperties properties;
    private final ScreeningRegistry screeningRegistry;
    private final RedisQueueRepository queueRepository;
    private final RedisActiveAdmissionRepository activeAdmissionRepository;
    private final RedisAdmissionLockRepository lockRepository;
    private final Clock clock;
    private final String instanceId;
    private final Counter runCounter;
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter lockMissCounter;
    private final Counter capacityHitCounter;

    @Autowired
    public QueueAdmissionService(
            QueueAdmissionProperties properties,
            ScreeningRegistry screeningRegistry,
            RedisQueueRepository queueRepository,
            RedisActiveAdmissionRepository activeAdmissionRepository,
            RedisAdmissionLockRepository lockRepository,
            MeterRegistry meterRegistry
    ) {
        this(
                properties,
                screeningRegistry,
                queueRepository,
                activeAdmissionRepository,
                lockRepository,
                Clock.systemUTC(),
                UUID.randomUUID().toString(),
                meterRegistry
        );
    }

    public QueueAdmissionService(
            QueueAdmissionProperties properties,
            ScreeningRegistry screeningRegistry,
            RedisQueueRepository queueRepository,
            RedisActiveAdmissionRepository activeAdmissionRepository,
            RedisAdmissionLockRepository lockRepository,
            Clock clock,
            String instanceId,
            MeterRegistry meterRegistry
    ) {
        this.properties = properties;
        this.screeningRegistry = screeningRegistry;
        this.queueRepository = queueRepository;
        this.activeAdmissionRepository = activeAdmissionRepository;
        this.lockRepository = lockRepository;
        this.clock = clock;
        this.instanceId = instanceId;
        this.runCounter = meterRegistry.counter("waiting.admission.runs");
        this.successCounter = meterRegistry.counter("waiting.admission.success");
        this.failureCounter = meterRegistry.counter("waiting.admission.failure");
        this.lockMissCounter = meterRegistry.counter("waiting.admission.lock.miss");
        this.capacityHitCounter = meterRegistry.counter("waiting.admission.capacity.hit");
    }

    public AdmissionRunResult admit(String screeningId) {
        long startedAt = clock.millis();
        runCounter.increment();

        if (!screeningRegistry.exists(screeningId)) {
            return result(screeningId, false, 0, 0, SkipReason.NO_VALID_SCREENING, startedAt);
        }

        boolean lockAcquired = lockRepository.acquire(screeningId, instanceId, properties.getLockTtl());
        if (!lockAcquired) {
            lockMissCounter.increment();
            return result(screeningId, false, 0, 0, SkipReason.LOCK_NOT_ACQUIRED, startedAt);
        }

        try {
            AdmissionBatch batch = selectBatch(screeningId);
            if (batch.remainingCapacity() <= 0) {
                capacityHitCounter.increment();
                return result(screeningId, true, 0, 0, SkipReason.ACTIVE_CAPACITY_REACHED, startedAt);
            }
            if (batch.selectedEntries().isEmpty()) {
                return result(screeningId, true, 0, 0, SkipReason.QUEUE_EMPTY, startedAt);
            }

            int successCount = 0;
            int failureCount = 0;
            for (QueueEntry entry : batch.selectedEntries()) {
                try {
                    if (activeAdmissionRepository.activate(screeningId, entry.token(), properties.getActiveTtl())) {
                        successCount++;
                    } else {
                        failureCount++;
                    }
                } catch (RuntimeException exception) {
                    failureCount++;
                    log.warn("Active admission write failed screeningId={} token={}", screeningId, entry.token(), exception);
                }
            }

            if (successCount > 0) {
                successCounter.increment(successCount);
            }
            if (failureCount > 0) {
                failureCounter.increment(failureCount);
            }

            return result(screeningId, true, successCount, failureCount, null, startedAt);
        } finally {
            lockRepository.release(screeningId, instanceId);
        }
    }

    AdmissionBatch selectBatch(String screeningId) {
        long activeCount = activeAdmissionRepository.countActive(screeningId);
        int remainingCapacity = (int) Math.max(0L, properties.getMaxActiveUsers() - activeCount);
        int selectionCount = Math.min(properties.getBatchSize(), remainingCapacity);
        List<QueueEntry> entries = selectionCount <= 0
                ? List.of()
                : queueRepository.popEarliest(screeningId, selectionCount);
        return new AdmissionBatch(screeningId, properties.getBatchSize(), remainingCapacity, entries);
    }

    private AdmissionRunResult result(
            String screeningId,
            boolean lockAcquired,
            int successCount,
            int failureCount,
            SkipReason skipReason,
            long startedAt
    ) {
        AdmissionRunResult result = new AdmissionRunResult(
                screeningId,
                instanceId,
                lockAcquired,
                successCount,
                failureCount,
                skipReason,
                clock.millis() - startedAt
        );

        log.info(
                "Queue admission completed screeningId={} instanceId={} lockAcquired={} successCount={} "
                        + "failureCount={} skipReason={} durationMillis={}",
                result.screeningId(),
                result.instanceId(),
                result.lockAcquired(),
                result.successCount(),
                result.failureCount(),
                result.skipReason(),
                result.durationMillis()
        );
        return result;
    }
}
