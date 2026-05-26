package com.movie.storage.ticket;

public enum IdempotencyClaimResult {
    CLAIMED,
    DUPLICATE_IN_PROGRESS,
    FINGERPRINT_CONFLICT,
    STORE_UNAVAILABLE
}
