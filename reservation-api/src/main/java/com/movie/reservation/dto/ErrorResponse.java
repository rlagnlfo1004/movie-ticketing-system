package com.movie.reservation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "오류 응답")
public record ErrorResponse(
        @Schema(description = "애플리케이션 오류 코드", example = "RESERVATION-400")
        String code,
        @Schema(description = "오류 메시지", example = "invalid ticket quantity")
        String message,
        @Schema(description = "재시도 가능 여부", example = "false")
        boolean retryable,
        @Schema(description = "상영 ID", example = "screening-1")
        String screeningId,
        @Schema(description = "요청에 사용된 Idempotency-Key", example = "issue-ticket-001")
        String idempotencyKey
) {
}
