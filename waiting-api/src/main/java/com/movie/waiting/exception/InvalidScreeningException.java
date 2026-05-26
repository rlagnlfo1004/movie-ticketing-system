package com.movie.waiting.exception;

import lombok.Getter;

@Getter
public class InvalidScreeningException extends RuntimeException {

    private final String screeningId;

    public InvalidScreeningException(String screeningId) {
        super("Invalid screening id: " + screeningId);
        this.screeningId = screeningId;
    }
}
