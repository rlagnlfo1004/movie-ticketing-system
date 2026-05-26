package com.movie.reservation.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.movie.reservation.config.ReservationProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class ActiveAdmissionClientTest {

    @Test
    void returnsActiveWhenRedisActiveKeyExists() {
        Fixture fixture = new Fixture();
        when(fixture.redisTemplate.hasKey("waiting:screening:1:active:active-token-123")).thenReturn(true);

        ActiveAdmissionResult result = fixture.client.validate("1", "active-token-123");

        assertThat(result.status()).isEqualTo(ActiveAdmissionResult.Status.ACTIVE);
    }

    @Test
    void returnsNotActiveWhenRedisActiveKeyDoesNotExist() {
        Fixture fixture = new Fixture();
        when(fixture.redisTemplate.hasKey("waiting:screening:1:active:waiting-token-123")).thenReturn(false);

        ActiveAdmissionResult result = fixture.client.validate("1", "waiting-token-123");

        assertThat(result.status()).isEqualTo(ActiveAdmissionResult.Status.NOT_ACTIVE);
    }

    @Test
    void usesConfiguredRedisKeyPrefix() {
        Fixture fixture = new Fixture();
        when(fixture.redisTemplate.hasKey("waiting:screening:2:active:active-token-123")).thenReturn(true);

        ActiveAdmissionResult result = fixture.client.validate("2", "active-token-123");

        assertThat(result.status()).isEqualTo(ActiveAdmissionResult.Status.ACTIVE);
    }

    private static class Fixture {
        private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        private final ActiveAdmissionClient client = new ActiveAdmissionClient(
                redisTemplate,
                new ReservationProperties(
                        new ReservationProperties.ActiveAdmission("waiting:screening"),
                        new ReservationProperties.Idempotency(Duration.ofMinutes(10)),
                        "^[A-Za-z0-9._:-]{8,256}$",
                        "^[A-Za-z0-9._:-]{8,128}$",
                        new ReservationProperties.PersistencePending(3),
                        new ReservationProperties.DefaultScreening(
                                "1",
                                "Demo Movie",
                                java.time.Instant.parse("2026-05-12T10:00:00Z"),
                                100
                        )
                )
        );
    }
}
