package com.movie.waiting.domain;

import java.util.List;

public record AdmissionBatch(
        String screeningId,
        int requestedBatchSize,
        int remainingCapacity,
        List<QueueEntry> selectedEntries
) {

    public AdmissionBatch {
        selectedEntries = List.copyOf(selectedEntries);
    }
}
