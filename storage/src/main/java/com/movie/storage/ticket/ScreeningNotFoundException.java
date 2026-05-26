package com.movie.storage.ticket;

public class ScreeningNotFoundException extends RuntimeException {

    private final String screeningId;

    public ScreeningNotFoundException(String screeningId) {
        super("Screening not found: " + screeningId);
        this.screeningId = screeningId;
    }

    public String getScreeningId() {
        return screeningId;
    }
}
