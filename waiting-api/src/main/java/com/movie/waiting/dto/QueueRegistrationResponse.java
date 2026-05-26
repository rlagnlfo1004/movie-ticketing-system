package com.movie.waiting.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "대기열 등록 결과")
public record QueueRegistrationResponse(
        @Schema(description = "상영 ID", example = "screening-1")
        String screeningId,
        @Schema(description = "대기열 토큰", example = "a8f2c1e0-9a34-4a6c-b8a8-f0f2fca38a11")
        String token,
        @Schema(description = "현재 대기열 등록 여부", example = "true")
        boolean registered,
        @Schema(description = "이번 호출로 신규 등록되었는지 여부", example = "true")
        boolean newlyRegistered,
        @Schema(description = "정렬에 사용되는 대기열 점수", example = "1715500000000")
        long score
) {
}
