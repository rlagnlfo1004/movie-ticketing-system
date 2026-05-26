package com.movie.reservation.exception;

import com.movie.reservation.dto.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ReservationApiExceptionHandler {

    @ExceptionHandler(ReservationApiException.class)
    ResponseEntity<ErrorResponse> handleReservationApiException(ReservationApiException exception) {
        ReservationErrorCode code = exception.getErrorCode();
        return ResponseEntity
                .status(code.status())
                .body(new ErrorResponse(
                        code.name(),
                        code.message(),
                        code.retryable(),
                        exception.getScreeningId(),
                        exception.getIdempotencyKey()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException exception) {
        ReservationErrorCode code = ReservationErrorCode.INVALID_TICKET_QUANTITY;
        return ResponseEntity
                .status(code.status())
                .body(new ErrorResponse(code.name(), code.message(), code.retryable(), null, null));
    }
}
