package com.movie.reservation.service;

import com.movie.reservation.dto.TicketHistoryResponse;
import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import com.movie.storage.ticket.ScreeningNotFoundException;
import com.movie.storage.ticket.TicketIssuanceCommandService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketHistoryService {

    private final TicketIssuanceCommandService commandService;

    public List<TicketHistoryResponse> findHistory(String screeningId, String queueToken) {
        try {
            return commandService.findHistory(screeningId, queueToken).stream()
                    .map(TicketHistoryResponse::from)
                    .toList();
        } catch (ScreeningNotFoundException exception) {
            throw new ReservationApiException(ReservationErrorCode.SCREENING_NOT_FOUND, screeningId, null);
        }
    }
}
