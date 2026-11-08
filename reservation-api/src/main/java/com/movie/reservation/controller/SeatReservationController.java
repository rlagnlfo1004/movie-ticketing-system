package com.movie.reservation.controller;

import com.movie.reservation.service.SeatHoldService;
import com.movie.reservation.service.SeatHoldService.HoldResponse;
import com.movie.reservation.service.SeatHoldService.MineResponse;
import com.movie.reservation.service.SeatHoldService.SeatsResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/screenings/{screeningId}")
@RequiredArgsConstructor
public class SeatReservationController {

    private final SeatHoldService service;

    public record HoldRequest(List<String> seats) {
    }

    @PostMapping(path = "/holds", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse hold(@PathVariable String screeningId,
                             @RequestHeader(value = "Queue-Token", required = false) String queueToken,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                             @RequestBody HoldRequest request) {
        return service.hold(screeningId, queueToken, idempotencyKey, request.seats());
    }

    @GetMapping("/seats")
    public SeatsResponse seats(@PathVariable String screeningId) {
        return service.seats(screeningId);
    }

    @GetMapping("/reservations/me")
    public MineResponse mine(@PathVariable String screeningId,
                             @RequestHeader(value = "Queue-Token", required = false) String queueToken) {
        return service.mine(screeningId, queueToken);
    }
}
