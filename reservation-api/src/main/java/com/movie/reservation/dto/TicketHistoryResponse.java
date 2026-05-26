package com.movie.reservation.dto;

import com.movie.storage.ticket.TicketIssuance;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "티켓 발급 이력 항목")
public record TicketHistoryResponse(
        @Schema(description = "상영 ID", example = "screening-1")
        String screeningId,
        @Schema(description = "발급된 티켓 ID", example = "1001")
        String ticketId,
        @Schema(description = "발급에 사용된 대기열 토큰", example = "a8f2c1e0-9a34-4a6c-b8a8-f0f2fca38a11")
        String queueToken,
        @Schema(description = "발급 수량", example = "2")
        int quantity,
        @Schema(description = "발급 상태", example = "ISSUED")
        String status,
        @Schema(description = "티켓 발급 시각(UTC)", example = "2026-05-12T12:34:56Z")
        Instant issuedAt
) {

    public static TicketHistoryResponse from(TicketIssuance issuance) {
        return new TicketHistoryResponse(
                issuance.getScreeningId(),
                String.valueOf(issuance.getId()),
                issuance.getQueueToken(),
                issuance.getQuantity(),
                issuance.getStatus().name(),
                issuance.getIssuedAt()
        );
    }
}
