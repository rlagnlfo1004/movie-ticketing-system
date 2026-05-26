package com.movie.waiting.service;

import com.movie.waiting.config.QueueRegistrationProperties;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ScreeningRegistry {

    private final Set<String> validScreeningIds;

    public ScreeningRegistry(QueueRegistrationProperties properties) {
        this.validScreeningIds = Set.copyOf(properties.getValidScreeningIds());
    }

    public boolean exists(String screeningId) {
        return validScreeningIds.contains(screeningId);
    }
}
