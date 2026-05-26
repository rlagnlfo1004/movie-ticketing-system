package com.movie.waiting.domain;

public record QueueEntry(
        String screeningId,
        String token,
        long score,
        boolean registered,
        boolean newlyRegistered
) {
}
