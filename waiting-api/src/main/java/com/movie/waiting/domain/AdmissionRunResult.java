package com.movie.waiting.domain;

public record AdmissionRunResult(
        String screeningId,
        String instanceId,
        boolean lockAcquired,
        int successCount,
        int failureCount,
        SkipReason skipReason,
        long durationMillis
) {

    public boolean skipped() {
        return skipReason != null;
    }

    public enum SkipReason {
        LOCK_NOT_ACQUIRED,
        ACTIVE_CAPACITY_REACHED,
        QUEUE_EMPTY,
        NO_VALID_SCREENING
    }
}
