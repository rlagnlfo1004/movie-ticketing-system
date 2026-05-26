package com.movie.reservation.client;

import com.movie.reservation.config.ReservationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ActiveAdmissionClient {

    private final StringRedisTemplate redisTemplate;
    private final ReservationProperties properties;

    public ActiveAdmissionResult validate(String screeningId, String queueToken) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(activeKey(screeningId, queueToken)))) {
            return new ActiveAdmissionResult(ActiveAdmissionResult.Status.ACTIVE);
        }
        return new ActiveAdmissionResult(ActiveAdmissionResult.Status.NOT_ACTIVE);
    }

    private String activeKey(String screeningId, String queueToken) {
        return properties.activeAdmission().redisKeyPrefix() + ":" + screeningId + ":active:" + queueToken;
    }
}
