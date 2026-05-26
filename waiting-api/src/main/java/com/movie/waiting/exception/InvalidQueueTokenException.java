package com.movie.waiting.exception;

public class InvalidQueueTokenException extends RuntimeException {

    public InvalidQueueTokenException() {
        super("Queue token must not be blank");
    }
}
