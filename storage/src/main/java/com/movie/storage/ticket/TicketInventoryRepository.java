package com.movie.storage.ticket;

public interface TicketInventoryRepository {

    void initialize(String screeningId, int quantity);

    boolean initializeIfAbsent(String screeningId, int quantity);

    TicketInventoryResult decrementOne(String screeningId, String queueToken);

    long remaining(String screeningId);
}
