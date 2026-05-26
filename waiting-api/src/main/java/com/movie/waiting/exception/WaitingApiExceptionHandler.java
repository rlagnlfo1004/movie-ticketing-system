package com.movie.waiting.exception;

import com.movie.waiting.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class WaitingApiExceptionHandler {

    @ExceptionHandler(InvalidScreeningException.class)
    ResponseEntity<ErrorResponse> handleInvalidScreening(InvalidScreeningException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_SCREENING", exception.getMessage()));
    }

    @ExceptionHandler(InvalidQueueTokenException.class)
    ResponseEntity<ErrorResponse> handleInvalidQueueToken(InvalidQueueTokenException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_TOKEN", exception.getMessage()));
    }
}
