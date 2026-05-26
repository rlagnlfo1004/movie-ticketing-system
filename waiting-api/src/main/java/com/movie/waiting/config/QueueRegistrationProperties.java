package com.movie.waiting.config;

import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "waiting.queue")
public class QueueRegistrationProperties {

    private String redisKeyPrefix = "waiting:screening";

    private Set<String> validScreeningIds = new LinkedHashSet<>(Set.of("1"));
}
