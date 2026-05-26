package com.movie.waiting.config;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "waiting.queue.status")
public class QueueStatusProperties {

    @Positive
    private long waitingPollAfterMillis = 1000L;

    @Positive
    private long activePollAfterMillis = 100L;

    @Positive
    private long notFoundPollAfterMillis = 3000L;
}
