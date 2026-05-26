package com.movie.reservation.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "reservation")
public record ReservationProperties(
        @NotNull ActiveAdmission activeAdmission,
        @NotNull Idempotency idempotency,
        @NotBlank String queueTokenPattern,
        @NotBlank String idempotencyKeyPattern,
        @NotNull PersistencePending persistencePending,
        @NotNull DefaultScreening defaultScreening
) {

    public record ActiveAdmission(@NotBlank String redisKeyPrefix) {
    }

    public record Idempotency(@NotNull Duration ttl) {
    }

    public Pattern idempotencyKeyCompiledPattern() {
        return Pattern.compile(idempotencyKeyPattern);
    }

    public record PersistencePending(int retryLimit) {
    }

    public record DefaultScreening(
            @NotBlank String id,
            @NotBlank String movieTitle,
            @NotNull Instant startsAt,
            int totalTicketCount
    ) {
    }
}
