package com.movie.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import com.movie.storage.ticket.ScreeningNotFoundException;
import com.movie.storage.ticket.TicketIssuance;
import com.movie.storage.ticket.TicketIssuanceCommandService;
import java.util.List;
import org.junit.jupiter.api.Test;

class TicketHistoryServiceTest {

    TicketIssuanceCommandService commandService = org.mockito.Mockito.mock(TicketIssuanceCommandService.class);
    TicketHistoryService service = new TicketHistoryService(commandService);

    @Test
    void returnsHistoryDtos() {
        when(commandService.findHistory("1", "active-token-123"))
                .thenReturn(List.of(new TicketIssuance("1", "active-token-123", "issuance-key-123", 1)));

        var history = service.findHistory("1", "active-token-123");

        assertThat(history).hasSize(1);
        assertThat(history.getFirst().screeningId()).isEqualTo("1");
        assertThat(history.getFirst().queueToken()).isEqualTo("active-token-123");
    }

    @Test
    void mapsMissingScreeningToReservationError() {
        when(commandService.findHistory("missing", null)).thenThrow(new ScreeningNotFoundException("missing"));

        assertThatThrownBy(() -> service.findHistory("missing", null))
                .isInstanceOfSatisfying(ReservationApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ReservationErrorCode.SCREENING_NOT_FOUND));
    }
}
