package com.movie.waiting.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "입장 가능 토큰 응답")
public record ActiveAdmissionResponse(
        @Schema(description = "상영 ID", example = "screening-1")
        String screeningId,
        @Schema(description = "대기열 토큰", example = "a8f2c1e0-9a34-4a6c-b8a8-f0f2fca38a11")
        String token,
        @Schema(description = "입장 상태", implementation = Status.class)
        Status status
) {

    @Schema(description = "입장 상태 타입")
    public enum Status {
        @Schema(description = "현재 입장 가능")
        ACTIVE,
        @Schema(description = "토큰이 없거나 입장 불가")
        NOT_FOUND
    }
}
