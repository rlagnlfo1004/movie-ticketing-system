package com.movie.reservation.controller;

import com.movie.reservation.controller.docs.TicketIssuanceControllerDocs;
import com.movie.reservation.dto.TicketIssuanceRequest;
import com.movie.reservation.dto.TicketIssuanceResponse;
import com.movie.reservation.dto.TicketHistoryResponse;
import com.movie.reservation.service.TicketHistoryService;
import com.movie.reservation.service.TicketIssuanceService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/screenings/{screeningId}/tickets")
@RequiredArgsConstructor
public class TicketIssuanceController implements TicketIssuanceControllerDocs {

    private final TicketIssuanceService ticketIssuanceService;
    private final TicketHistoryService ticketHistoryService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TicketIssuanceResponse issue(
            @PathVariable String screeningId,
            @RequestHeader(value = "Queue-Token", required = false) String queueToken,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TicketIssuanceRequest request
    ) {
        return ticketIssuanceService.issue(screeningId, queueToken, idempotencyKey, request);
    }

    @GetMapping(path = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TicketHistoryResponse> history(
            @PathVariable String screeningId,
            @RequestParam(required = false) String queueToken
    ) {
        return ticketHistoryService.findHistory(screeningId, queueToken);
    }
}
