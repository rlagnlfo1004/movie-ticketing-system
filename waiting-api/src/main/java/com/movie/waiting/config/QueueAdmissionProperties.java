package com.movie.waiting.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "waiting.queue.admission")
public class QueueAdmissionProperties {

    @DurationMin(millis = 1)
    private Duration schedulerInterval = Duration.ofSeconds(5);

    @Positive
    private int batchSize = 10;

    @DurationMin(millis = 1)
    private Duration activeTtl = Duration.ofMinutes(5);

    @Positive
    private int maxActiveUsers = 100;

    @DurationMin(millis = 1)
    private Duration lockTtl = Duration.ofSeconds(10);

    @NotEmpty
    private Set<String> screeningIds = new LinkedHashSet<>(Set.of("1"));
}
