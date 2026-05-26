package com.movie.waiting.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.movie.waiting.domain.QueueStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "대기열 상태 응답")
public record QueueStatusResponse(
        @Schema(description = "상영 ID", example = "screening-1")
        String screeningId,
        @Schema(description = "대기열 토큰", example = "a8f2c1e0-9a34-4a6c-b8a8-f0f2fca38a11")
        String token,
        @Schema(description = "대기열 상태", example = "WAITING")
        QueueStatus status,
        @Schema(description = "현재 순번(대기 상태일 때만 제공)", example = "42", nullable = true)
        Long rank,
        @Schema(description = "앞에 남은 대기 인원(대기 상태일 때만 제공)", example = "41", nullable = true)
        Long waitingCount,
        @Schema(description = "다음 조회 권장 간격(ms)", example = "3000", nullable = true)
        Long pollAfterMillis
) {
}
