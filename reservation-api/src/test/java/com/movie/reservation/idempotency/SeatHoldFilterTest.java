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
import com.movie.reservation.client.MockPgClient;
import com.movie.reservation.config.ReservationProperties;
import com.movie.reservation.controller.SeatReservationController;
import com.movie.reservation.exception.ReservationApiExceptionHandler;
import com.movie.reservation.service.SeatHoldService;
import com.movie.reservation.service.SeatHoldService.HoldResponse;
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

class SeatHoldFilterTest {

    private final SeatHoldService service = Mockito.mock(SeatHoldService.class);
    private final MockPgClient pg = Mockito.mock(MockPgClient.class);
    private final IdempotencyRecordRepository records = Mockito.mock(IdempotencyRecordRepository.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        ReservationProperties properties = new ReservationProperties(
                new ReservationProperties.Idempotency(Duration.ofMinutes(10)),
                "^[A-Za-z0-9._:-]{8,256}$", "^[A-Za-z0-9._:-]{8,128}$");
        mvc = MockMvcBuilders.standaloneSetup(new SeatReservationController(service, pg))
                .addFilters(new IdempotencyFilter(new IdempotencyFingerprint(new JsonCanonicalizer(mapper)),
                        new IdempotencyValidator(properties, records), mapper))
                .setControllerAdvice(new ReservationApiExceptionHandler())
                .build();
    }

    @Test
    void rejectsMissingKeyBeforeHoldingSeats() throws Exception {
        mvc.perform(request().content("{\"seats\":[\"A-12\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        verify(service, never()).hold(any(), any(), any(), any());
    }

    @Test
    void rejectsSameKeyForDifferentRequest() throws Exception {
        when(records.claim(eq("1"), eq("hold-key-123"), any(), eq(Duration.ofMinutes(10))))
                .thenReturn(IdempotencyClaimResult.FINGERPRINT_CONFLICT);
        mvc.perform(request().header("Idempotency-Key", "hold-key-123")
                        .content("{\"seats\":[\"A-12\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        verify(service, never()).hold(any(), any(), any(), any());
    }

    @Test
    void forwardsClaimedHoldBodyToController() throws Exception {
        when(records.claim(eq("1"), eq("hold-key-123"), any(), eq(Duration.ofMinutes(10))))
                .thenReturn(IdempotencyClaimResult.CLAIMED);
        when(service.hold(eq("1"), eq("active-token-123"), eq("hold-key-123"), any()))
                .thenReturn(new HoldResponse("hold-1", "payment-1", 15000, Instant.parse("2026-11-08T08:00:00Z")));
        mvc.perform(request().header("Idempotency-Key", "hold-key-123")
                        .content("{\"seats\":[\"A-12\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(15000));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
        return post("/api/v1/screenings/1/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Queue-Token", "active-token-123");
    }
}
