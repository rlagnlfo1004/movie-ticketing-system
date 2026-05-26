package com.movie.waiting.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "대기열 등록 요청")
public record QueueRegistrationRequest(
        @Schema(description = "재등록할 기존 토큰, 없으면 새 토큰 발급", example = "a8f2c1e0-9a34-4a6c-b8a8-f0f2fca38a11")
        String token
) {
}
