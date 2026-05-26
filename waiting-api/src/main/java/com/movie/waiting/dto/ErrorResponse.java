package com.movie.waiting.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "오류 응답")
public record ErrorResponse(
        @Schema(description = "애플리케이션 오류 코드", example = "WAITING-404")
        String code,
        @Schema(description = "오류 메시지", example = "screening not found")
        String message
) {
}
