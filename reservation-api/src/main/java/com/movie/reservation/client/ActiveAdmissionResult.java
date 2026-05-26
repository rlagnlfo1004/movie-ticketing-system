package com.movie.reservation.client;

public record ActiveAdmissionResult(Status status) {

    public enum Status {
        ACTIVE,
        NOT_ACTIVE
    }

    public boolean active() {
        return status == Status.ACTIVE;
    }
}
