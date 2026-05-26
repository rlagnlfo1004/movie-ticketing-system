package com.movie.reservation.idempotency;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movie.reservation.config.ReservationProperties;
import com.movie.reservation.controller.TicketIssuanceController;
import com.movie.reservation.dto.TicketIssuanceResponse;
import com.movie.reservation.exception.ReservationApiExceptionHandler;
import com.movie.reservation.service.TicketHistoryService;
import com.movie.reservation.service.TicketIssuanceService;
import com.movie.storage.ticket.IdempotencyClaimResult;
import com.movie.storage.ticket.IdempotencyRecordRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IdempotencyFilterTest {

    private static final String VALID_KEY = "idem-key-123";
    private static final String VALID_TOKEN = "active-token-123";

    TicketIssuanceService ticketIssuanceService = Mockito.mock(TicketIssuanceService.class);
    TicketHistoryService ticketHistoryService = Mockito.mock(TicketHistoryService.class);
    IdempotencyRecordRepository repository = Mockito.mock(IdempotencyRecordRepository.class);
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
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
        IdempotencyFilter filter = new IdempotencyFilter(
                new IdempotencyFingerprint(new JsonCanonicalizer(objectMapper)),
                new IdempotencyValidator(properties, repository),
                objectMapper
        );
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TicketIssuanceController(ticketIssuanceService, ticketHistoryService))
                .addFilters(filter)
                .setControllerAdvice(new ReservationApiExceptionHandler())
                .build();
    }

    @Test
    void missingAndBlankIdempotencyKeyAreRejectedBeforeService() throws Exception {
        mockMvc.perform(ticketRequest().content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        mockMvc.perform(ticketRequest().header("Idempotency-Key", "   ").content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        verify(ticketIssuanceService, never()).issue(any(), any(), any(), any());
        verify(repository, never()).claim(any(), any(), any(), any());
    }

    @Test
    void malformedIdempotencyKeyIsRejectedBeforeService() throws Exception {
        mockMvc.perform(ticketRequest().header("Idempotency-Key", "bad").content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));

        verify(ticketIssuanceService, never()).issue(any(), any(), any(), any());
        verify(repository, never()).claim(any(), any(), any(), any());
    }

    @Test
    void sameKeySameFingerprintDuplicateIsRejectedBeforeService() throws Exception {
        when(repository.claim(eq("1"), eq(VALID_KEY), any(String.class), eq(Duration.ofMinutes(10))))
                .thenReturn(IdempotencyClaimResult.DUPLICATE_IN_PROGRESS);

        mockMvc.perform(validTicketRequest().content("{\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_REQUEST_IN_PROGRESS"));

        verify(ticketIssuanceService, never()).issue(any(), any(), any(), any());
    }

    @Test
    void sameKeyDifferentBodyOrQueueTokenConflictsBeforeService() throws Exception {
        when(repository.claim(eq("1"), eq(VALID_KEY), any(String.class), eq(Duration.ofMinutes(10))))
                .thenReturn(IdempotencyClaimResult.FINGERPRINT_CONFLICT);

        mockMvc.perform(validTicketRequest().content("{\"quantity\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        mockMvc.perform(ticketRequest()
                        .header("Queue-Token", "other-token-123")
                        .header("Idempotency-Key", VALID_KEY)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        verify(ticketIssuanceService, never()).issue(any(), any(), any(), any());
    }

    @Test
    void storeUnavailableFailsClosedBeforeService() throws Exception {
        when(repository.claim(eq("1"), eq(VALID_KEY), any(String.class), eq(Duration.ofMinutes(10))))
                .thenReturn(IdempotencyClaimResult.STORE_UNAVAILABLE);

        mockMvc.perform(validTicketRequest().content("{\"quantity\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_STORE_UNAVAILABLE"));

        verify(ticketIssuanceService, never()).issue(any(), any(), any(), any());
    }

    @Test
    void claimedRequestForwardsCachedBodyToController() throws Exception {
        when(repository.claim(eq("1"), eq(VALID_KEY), any(String.class), eq(Duration.ofMinutes(10))))
                .thenReturn(IdempotencyClaimResult.CLAIMED);
        when(ticketIssuanceService.issue(any(), any(), any(), any()))
                .thenReturn(new TicketIssuanceResponse("1", "10", 1, "SUCCEEDED", Instant.parse("2026-05-12T00:00:00Z")));

        mockMvc.perform(validTicketRequest().content("{\"quantity\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validTicketRequest() {
        return ticketRequest()
                .header("Queue-Token", VALID_TOKEN)
                .header("Idempotency-Key", VALID_KEY);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder ticketRequest() {
        return post("/api/v1/screenings/1/tickets")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Queue-Token", VALID_TOKEN);
    }
}
