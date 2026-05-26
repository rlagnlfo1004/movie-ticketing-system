package com.movie.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movie.reservation.client.ActiveAdmissionClient;
import com.movie.reservation.client.ActiveAdmissionResult;
import com.movie.reservation.config.ReservationProperties;
import com.movie.reservation.dto.TicketIssuanceRequest;
import com.movie.reservation.dto.TicketIssuanceResponse;
import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import com.movie.storage.ticket.TicketInventoryRepository;
import com.movie.storage.ticket.TicketInventoryResult;
import com.movie.storage.ticket.ScreeningNotFoundException;
import com.movie.storage.ticket.TicketIssuance;
import com.movie.storage.ticket.TicketIssuanceCommandService;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TicketIssuanceServiceTest {

    private static final String SCREENING_ID = "1";
    private static final String QUEUE_TOKEN = "active-token-123";
    private static final String IDEMPOTENCY_KEY = "issuance-key-123";

    @Mock
    ActiveAdmissionClient activeAdmissionClient;

    @Mock
    TicketInventoryRepository inventoryRepository;

    @Mock
    TicketIssuanceCommandService commandService;

    TicketIssuanceService service;

    @BeforeEach
    void setUp() {
        ReservationProperties properties = new ReservationProperties(
                new ReservationProperties.ActiveAdmission("waiting:screening"),
                new ReservationProperties.Idempotency(Duration.ofMinutes(10)),
                "^[A-Za-z0-9._:-]{8,256}$",
                "^[A-Za-z0-9._:-]{8,128}$",
                new ReservationProperties.PersistencePending(3),
                defaultScreening()
        );
        service = new TicketIssuanceService(activeAdmissionClient, inventoryRepository, commandService, properties);
    }

    @Test
    void activeUserReceivesTicket() {
        TicketIssuance saved = new TicketIssuance(SCREENING_ID, QUEUE_TOKEN, IDEMPOTENCY_KEY, 1);
        when(commandService.findByIdempotencyKey(SCREENING_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(commandService.findByQueueToken(SCREENING_ID, QUEUE_TOKEN)).thenReturn(Optional.empty());
        when(activeAdmissionClient.validate(SCREENING_ID, QUEUE_TOKEN))
                .thenReturn(new ActiveAdmissionResult(ActiveAdmissionResult.Status.ACTIVE));
        when(inventoryRepository.decrementOne(SCREENING_ID, QUEUE_TOKEN)).thenReturn(TicketInventoryResult.ISSUED);
        when(commandService.saveSuccess(SCREENING_ID, QUEUE_TOKEN, IDEMPOTENCY_KEY, 1)).thenReturn(saved);

        TicketIssuanceResponse response = service.issue(
                SCREENING_ID,
                QUEUE_TOKEN,
                IDEMPOTENCY_KEY,
                new TicketIssuanceRequest(1)
        );

        assertThat(response.screeningId()).isEqualTo(SCREENING_ID);
        assertThat(response.quantity()).isEqualTo(1);
        assertThat(response.status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void sameIdempotencyKeyReplaysExistingSuccessWithoutInventoryMutation() {
        TicketIssuance existing = new TicketIssuance(SCREENING_ID, QUEUE_TOKEN, IDEMPOTENCY_KEY, 1);
        when(commandService.findByIdempotencyKey(SCREENING_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

        TicketIssuanceResponse response = service.issue(SCREENING_ID, QUEUE_TOKEN, IDEMPOTENCY_KEY, new TicketIssuanceRequest(1));

        assertThat(response.status()).isEqualTo("SUCCEEDED");
        verify(inventoryRepository, never()).decrementOne(SCREENING_ID, QUEUE_TOKEN);
    }

    @Test
    void conflictingIdempotencyKeyIsRejected() {
        TicketIssuance existing = new TicketIssuance(SCREENING_ID, "other-token-123", IDEMPOTENCY_KEY, 1);
        when(commandService.findByIdempotencyKey(SCREENING_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

        assertError(
                () -> service.issue(SCREENING_ID, QUEUE_TOKEN, IDEMPOTENCY_KEY, new TicketIssuanceRequest(1)),
                ReservationErrorCode.IDEMPOTENCY_KEY_CONFLICT
        );
    }

    @Test
    void nonActiveAdmissionDoesNotConsumeInventory() {
        when(commandService.findByIdempotencyKey(SCREENING_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(commandService.findByQueueToken(SCREENING_ID, QUEUE_TOKEN)).thenReturn(Optional.empty());
        when(activeAdmissionClient.validate(SCREENING_ID, QUEUE_TOKEN))
                .thenReturn(new ActiveAdmissionResult(ActiveAdmissionResult.Status.NOT_ACTIVE));

        assertError(
                () -> service.issue(SCREENING_ID, QUEUE_TOKEN, IDEMPOTENCY_KEY, new TicketIssuanceRequest(1)),
                ReservationErrorCode.QUEUE_TOKEN_NOT_ACTIVE
        );
        verify(inventoryRepository, never()).decrementOne(SCREENING_ID, QUEUE_TOKEN);
    }

    @Test
    void duplicateQueueTokenIsRejectedBeforeInventoryMutation() {
        when(commandService.findByIdempotencyKey(SCREENING_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(commandService.findByQueueToken(SCREENING_ID, QUEUE_TOKEN))
                .thenReturn(Optional.of(new TicketIssuance(SCREENING_ID, QUEUE_TOKEN, "other-key-123", 1)));

        assertError(
                () -> service.issue(SCREENING_ID, QUEUE_TOKEN, IDEMPOTENCY_KEY, new TicketIssuanceRequest(1)),
                ReservationErrorCode.DUPLICATE_TICKET_ISSUANCE
        );
        verify(inventoryRepository, never()).decrementOne(SCREENING_ID, QUEUE_TOKEN);
    }

    @Test
    void soldOutInventoryMapsToStableError() {
        when(commandService.findByIdempotencyKey(SCREENING_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(commandService.findByQueueToken(SCREENING_ID, QUEUE_TOKEN)).thenReturn(Optional.empty());
        when(activeAdmissionClient.validate(SCREENING_ID, QUEUE_TOKEN))
                .thenReturn(new ActiveAdmissionResult(ActiveAdmissionResult.Status.ACTIVE));
        when(inventoryRepository.decrementOne(SCREENING_ID, QUEUE_TOKEN)).thenReturn(TicketInventoryResult.SOLD_OUT);

        assertError(
                () -> service.issue(SCREENING_ID, QUEUE_TOKEN, IDEMPOTENCY_KEY, new TicketIssuanceRequest(1)),
                ReservationErrorCode.TICKET_SOLD_OUT
        );
    }

    @Test
    void persistenceFailureCreatesRecoveryRecordAndReturnsRetryableError() {
        RuntimeException failure = new RuntimeException("database unavailable");
        when(commandService.findByIdempotencyKey(SCREENING_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(commandService.findByQueueToken(SCREENING_ID, QUEUE_TOKEN)).thenReturn(Optional.empty());
        when(activeAdmissionClient.validate(SCREENING_ID, QUEUE_TOKEN))
                .thenReturn(new ActiveAdmissionResult(ActiveAdmissionResult.Status.ACTIVE));
        when(inventoryRepository.decrementOne(SCREENING_ID, QUEUE_TOKEN)).thenReturn(TicketInventoryResult.ISSUED);
        when(commandService.saveSuccess(SCREENING_ID, QUEUE_TOKEN, IDEMPOTENCY_KEY, 1)).thenThrow(failure);

        assertError(
                () -> service.issue(SCREENING_ID, QUEUE_TOKEN, IDEMPOTENCY_KEY, new TicketIssuanceRequest(1)),
                ReservationErrorCode.TICKET_ISSUANCE_PERSISTENCE_FAILED
        );
        verify(commandService).savePersistencePending(SCREENING_ID, QUEUE_TOKEN, IDEMPOTENCY_KEY, 1, "database unavailable");
    }

    @Test
    void missingScreeningMapsToNotFoundAfterInventoryDeduction() {
        when(commandService.findByIdempotencyKey(SCREENING_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(commandService.findByQueueToken(SCREENING_ID, QUEUE_TOKEN)).thenReturn(Optional.empty());
        when(activeAdmissionClient.validate(SCREENING_ID, QUEUE_TOKEN))
                .thenReturn(new ActiveAdmissionResult(ActiveAdmissionResult.Status.ACTIVE));
        when(inventoryRepository.decrementOne(SCREENING_ID, QUEUE_TOKEN)).thenReturn(TicketInventoryResult.ISSUED);
        when(commandService.saveSuccess(SCREENING_ID, QUEUE_TOKEN, IDEMPOTENCY_KEY, 1))
                .thenThrow(new ScreeningNotFoundException(SCREENING_ID));

        assertError(
                () -> service.issue(SCREENING_ID, QUEUE_TOKEN, IDEMPOTENCY_KEY, new TicketIssuanceRequest(1)),
                ReservationErrorCode.SCREENING_NOT_FOUND
        );
    }

    @Test
    void invalidInputsReturnStableValidationErrors() {
        assertError(
                () -> service.issue(SCREENING_ID, null, IDEMPOTENCY_KEY, new TicketIssuanceRequest(1)),
                ReservationErrorCode.QUEUE_TOKEN_REQUIRED
        );
        assertError(
                () -> service.issue(SCREENING_ID, "bad", IDEMPOTENCY_KEY, new TicketIssuanceRequest(1)),
                ReservationErrorCode.QUEUE_TOKEN_INVALID
        );
        assertError(
                () -> service.issue(SCREENING_ID, QUEUE_TOKEN, null, new TicketIssuanceRequest(1)),
                ReservationErrorCode.IDEMPOTENCY_KEY_REQUIRED
        );
        assertError(
                () -> service.issue(SCREENING_ID, QUEUE_TOKEN, "bad", new TicketIssuanceRequest(1)),
                ReservationErrorCode.IDEMPOTENCY_KEY_INVALID
        );
        assertError(
                () -> service.issue(SCREENING_ID, QUEUE_TOKEN, IDEMPOTENCY_KEY, new TicketIssuanceRequest(2)),
                ReservationErrorCode.INVALID_TICKET_QUANTITY
        );
    }

    private void assertError(Runnable action, ReservationErrorCode expectedCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ReservationApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expectedCode));
    }

    private ReservationProperties.DefaultScreening defaultScreening() {
        return new ReservationProperties.DefaultScreening(
                "1",
                "Demo Movie",
                java.time.Instant.parse("2026-05-12T10:00:00Z"),
                100
        );
    }
}
