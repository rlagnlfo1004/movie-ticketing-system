package com.movie.waiting.service;

import com.movie.waiting.domain.QueueEntry;
import com.movie.waiting.exception.InvalidScreeningException;
import com.movie.waiting.repository.RedisQueueRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QueueRegistrationService {

    private final ScreeningRegistry screeningRegistry;
    private final RedisQueueRepository redisQueueRepository;
    private final Clock clock;
    private final Supplier<String> tokenSupplier;

    @Autowired
    public QueueRegistrationService(ScreeningRegistry screeningRegistry, RedisQueueRepository redisQueueRepository) {
        this(screeningRegistry, redisQueueRepository, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    QueueRegistrationService(
            ScreeningRegistry screeningRegistry,
            RedisQueueRepository redisQueueRepository,
            Clock clock,
            Supplier<String> tokenSupplier
    ) {
        this.screeningRegistry = screeningRegistry;
        this.redisQueueRepository = redisQueueRepository;
        this.clock = clock;
        this.tokenSupplier = tokenSupplier;
    }

    public QueueEntry register(String screeningId, String requestedToken) {
        if (!screeningRegistry.exists(screeningId)) {
            throw new InvalidScreeningException(screeningId);
        }

        String token = StringUtils.hasText(requestedToken) ? requestedToken.trim() : tokenSupplier.get();
        return redisQueueRepository.register(screeningId, token, clock.millis());
    }
}
