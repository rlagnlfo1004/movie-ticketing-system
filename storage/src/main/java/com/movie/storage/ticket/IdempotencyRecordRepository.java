package com.movie.storage.ticket;

import java.time.Duration;

public interface IdempotencyRecordRepository {

    IdempotencyClaimResult claim(String screeningId, String idempotencyKey, String fingerprint, Duration ttl);
}
