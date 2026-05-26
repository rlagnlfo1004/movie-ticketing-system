package com.movie.storage.ticket;

import java.time.Instant;

public record IdempotencyRecord(
        String fingerprint,
        String status,
        Instant createdAt
) {
    private static final String DELIMITER = "|";
    private static final String IN_PROGRESS = "IN_PROGRESS";

    public static IdempotencyRecord inProgress(String fingerprint, Instant createdAt) {
        return new IdempotencyRecord(fingerprint, IN_PROGRESS, createdAt);
    }

    String serialize() {
        return fingerprint + DELIMITER + status + DELIMITER + createdAt.toString();
    }

    static IdempotencyRecord deserialize(String value) {
        String[] parts = value.split("\\|", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid idempotency record value");
        }
        return new IdempotencyRecord(parts[0], parts[1], Instant.parse(parts[2]));
    }
}
