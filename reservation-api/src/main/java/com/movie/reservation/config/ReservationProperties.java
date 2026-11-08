package com.movie.reservation.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "reservation")
public record ReservationProperties(
        @NotNull Idempotency idempotency,
        @NotBlank String queueTokenPattern,
        @NotBlank String idempotencyKeyPattern
) {

    public record Idempotency(@NotNull Duration ttl) {
    }

    public Pattern idempotencyKeyCompiledPattern() {
        return Pattern.compile(idempotencyKeyPattern);
    }

}
