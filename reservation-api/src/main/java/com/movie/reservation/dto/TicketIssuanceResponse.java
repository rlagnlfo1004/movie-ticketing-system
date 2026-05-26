package com.movie.reservation.dto;

import com.movie.storage.ticket.TicketIssuance;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "티켓 발급 응답")
public record TicketIssuanceResponse(
        @Schema(description = "상영 ID", example = "screening-1")
        String screeningId,
        @Schema(description = "발급된 티켓 ID", example = "1001")
        String ticketId,
        @Schema(description = "발급 수량", example = "2")
        int quantity,
        @Schema(description = "발급 상태", example = "ISSUED")
        String status,
        @Schema(description = "티켓 발급 시각(UTC)", example = "2026-05-12T12:34:56Z")
        Instant issuedAt
) {

    public static TicketIssuanceResponse from(TicketIssuance issuance) {
        return new TicketIssuanceResponse(
                issuance.getScreeningId(),
                String.valueOf(issuance.getId()),
                issuance.getQuantity(),
                issuance.getStatus().name(),
                issuance.getIssuedAt()
        );
    }
}
