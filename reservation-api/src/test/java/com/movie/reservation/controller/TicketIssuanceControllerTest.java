package com.movie.reservation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.movie.reservation.dto.TicketIssuanceRequest;
import com.movie.reservation.dto.TicketIssuanceResponse;
import com.movie.reservation.dto.TicketHistoryResponse;
import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationApiExceptionHandler;
import com.movie.reservation.exception.ReservationErrorCode;
import com.movie.reservation.config.ReservationProperties;
import com.movie.reservation.idempotency.IdempotencyFilter;
import com.movie.reservation.idempotency.IdempotencyFingerprint;
import com.movie.reservation.idempotency.IdempotencyValidator;
import com.movie.reservation.idempotency.JsonCanonicalizer;
import com.movie.reservation.service.TicketIssuanceService;
import com.movie.reservation.service.TicketHistoryService;
import com.movie.storage.ticket.IdempotencyClaimResult;
import com.movie.storage.ticket.IdempotencyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.mockito.Mockito;
import java.util.List;

class TicketIssuanceControllerTest {

    TicketIssuanceService ticketIssuanceService = Mockito.mock(TicketIssuanceService.class);
    TicketHistoryService ticketHistoryService = Mockito.mock(TicketHistoryService.class);
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TicketIssuanceController(ticketIssuanceService, ticketHistoryService))
                .setControllerAdvice(new ReservationApiExceptionHandler())
                .build();
    }

    @Test
    void returnsCreatedForSuccessfulIssuance() throws Exception {
        when(ticketIssuanceService.issue(eq("1"), eq("active-token-123"), eq("issuance-key-123"), any(TicketIssuanceRequest.class)))
                .thenReturn(new TicketIssuanceResponse("1", "10", 1, "SUCCEEDED", Instant.parse("2026-05-12T00:00:00Z")));

        mockMvc.perform(post("/api/v1/screenings/1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Queue-Token", "active-token-123")
                        .header("Idempotency-Key", "issuance-key-123")
                        .content("{\"quantity\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.screeningId").value("1"))
                .andExpect(jsonPath("$.ticketId").value("10"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void mapsInvalidQuantityToBadRequest() throws Exception {
        when(ticketIssuanceService.issue(eq("1"), eq("active-token-123"), eq("issuance-key-123"), any(TicketIssuanceRequest.class)))
                .thenThrow(new ReservationApiException(ReservationErrorCode.INVALID_TICKET_QUANTITY, "1", "issuance-key-123"));

        mockMvc.perform(post("/api/v1/screenings/1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Queue-Token", "active-token-123")
                        .header("Idempotency-Key", "issuance-key-123")
                        .content("{\"quantity\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TICKET_QUANTITY"));
    }

    @Test
    void mapsNonActiveTokenToForbidden() throws Exception {
        when(ticketIssuanceService.issue(eq("1"), eq("waiting-token-123"), eq("issuance-key-123"), any(TicketIssuanceRequest.class)))
                .thenThrow(new ReservationApiException(ReservationErrorCode.QUEUE_TOKEN_NOT_ACTIVE, "1", "issuance-key-123"));

        mockMvc.perform(post("/api/v1/screenings/1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Queue-Token", "waiting-token-123")
                        .header("Idempotency-Key", "issuance-key-123")
                        .content("{\"quantity\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUEUE_TOKEN_NOT_ACTIVE"));
    }

    @Test
    void mapsDuplicateToConflict() throws Exception {
        when(ticketIssuanceService.issue(eq("1"), eq("active-token-123"), eq("issuance-key-123"), any(TicketIssuanceRequest.class)))
                .thenThrow(new ReservationApiException(ReservationErrorCode.DUPLICATE_TICKET_ISSUANCE, "1", "issuance-key-123"));

        mockMvc.perform(post("/api/v1/screenings/1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Queue-Token", "active-token-123")
                        .header("Idempotency-Key", "issuance-key-123")
                        .content("{\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_TICKET_ISSUANCE"));
    }

    @Test
    void returnsTicketHistory() throws Exception {
        when(ticketHistoryService.findHistory("1", "active-token-123"))
                .thenReturn(List.of(new TicketHistoryResponse(
                        "1",
                        "10",
                        "active-token-123",
                        1,
                        "SUCCEEDED",
                        Instant.parse("2026-05-12T00:00:00Z")
                )));

        mockMvc.perform(get("/api/v1/screenings/1/tickets/history")
                        .queryParam("queueToken", "active-token-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].screeningId").value("1"))
                .andExpect(jsonPath("$[0].queueToken").value("active-token-123"))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"));
    }

    @Test
    void idempotencyStoreUnavailableDoesNotInvokeService() throws Exception {
        IdempotencyRecordRepository repository = Mockito.mock(IdempotencyRecordRepository.class);
        when(repository.claim(eq("1"), eq("issuance-key-123"), any(String.class), eq(Duration.ofMinutes(10))))
                .thenReturn(IdempotencyClaimResult.STORE_UNAVAILABLE);
        ObjectMapper objectMapper = new ObjectMapper();
        ReservationProperties properties = new ReservationProperties(
                new ReservationProperties.ActiveAdmission("waiting:screening"),
                new ReservationProperties.Idempotency(Duration.ofMinutes(10)),
                "^[A-Za-z0-9._:-]{8,256}$",
                "^[A-Za-z0-9._:-]{8,128}$",
                new ReservationProperties.PersistencePending(3),
                new ReservationProperties.DefaultScreening(
                        "1",
                        "Demo Movie",
                        Instant.parse("2026-05-12T10:00:00Z"),
                        100
                )
        );
        MockMvc filteredMockMvc = MockMvcBuilders
                .standaloneSetup(new TicketIssuanceController(ticketIssuanceService, ticketHistoryService))
                .addFilters(new IdempotencyFilter(
                        new IdempotencyFingerprint(new JsonCanonicalizer(objectMapper)),
                        new IdempotencyValidator(properties, repository),
                        objectMapper
                ))
                .setControllerAdvice(new ReservationApiExceptionHandler())
                .build();

        filteredMockMvc.perform(post("/api/v1/screenings/1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Queue-Token", "active-token-123")
                        .header("Idempotency-Key", "issuance-key-123")
                        .content("{\"quantity\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_STORE_UNAVAILABLE"));

        verify(ticketIssuanceService, never()).issue(any(), any(), any(), any());
    }
}
