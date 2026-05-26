package com.movie.storage.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movie.storage.screening.ScreeningRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TicketIssuanceCommandServiceTest {

    @Mock
    TicketIssuanceRepository ticketIssuanceRepository;

    @Mock
    PersistencePendingIssuanceRepository persistencePendingIssuanceRepository;

    @Mock
    ScreeningRepository screeningRepository;

    @InjectMocks
    TicketIssuanceCommandService service;

    @Test
    void savesSuccessfulIssuance() {
        ArgumentCaptor<TicketIssuance> captor = ArgumentCaptor.forClass(TicketIssuance.class);
        when(screeningRepository.existsById("1")).thenReturn(true);
        when(ticketIssuanceRepository.saveAndFlush(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        TicketIssuance saved = service.saveSuccess("1", "active-token-123", "issuance-key-123", 1);

        assertThat(saved.getScreeningId()).isEqualTo("1");
        assertThat(saved.getQueueToken()).isEqualTo("active-token-123");
        assertThat(saved.getStatus()).isEqualTo(TicketIssuanceStatus.SUCCEEDED);
    }

    @Test
    void savesPersistencePendingIssuance() {
        ArgumentCaptor<PersistencePendingIssuance> captor = ArgumentCaptor.forClass(PersistencePendingIssuance.class);
        when(persistencePendingIssuanceRepository.saveAndFlush(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        PersistencePendingIssuance saved = service.savePersistencePending("1", "active-token-123", "issuance-key-123", 1, "db failed");

        assertThat(saved.getStatus()).isEqualTo(PersistencePendingStatus.PENDING);
        assertThat(saved.isInventoryConsumed()).isTrue();
        assertThat(saved.getFailureReason()).isEqualTo("db failed");
    }

    @Test
    void delegatesLookupMethods() {
        TicketIssuance issuance = new TicketIssuance("1", "active-token-123", "issuance-key-123", 1);
        when(ticketIssuanceRepository.findByScreeningIdAndQueueToken("1", "active-token-123")).thenReturn(Optional.of(issuance));
        when(ticketIssuanceRepository.findByScreeningIdAndIdempotencyKey("1", "issuance-key-123")).thenReturn(Optional.of(issuance));

        assertThat(service.findByQueueToken("1", "active-token-123")).contains(issuance);
        assertThat(service.findByIdempotencyKey("1", "issuance-key-123")).contains(issuance);
        verify(ticketIssuanceRepository).findByScreeningIdAndQueueToken("1", "active-token-123");
        verify(ticketIssuanceRepository).findByScreeningIdAndIdempotencyKey("1", "issuance-key-123");
    }

    @Test
    void rejectsMissingScreeningBeforeSave() {
        when(screeningRepository.existsById("missing")).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.saveSuccess("missing", "active-token-123", "issuance-key-123", 1)
                )
                .isInstanceOf(ScreeningNotFoundException.class);
    }
}
