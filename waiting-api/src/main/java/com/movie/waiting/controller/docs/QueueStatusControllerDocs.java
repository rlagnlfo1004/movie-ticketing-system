package com.movie.waiting.controller.docs;

import com.movie.waiting.dto.ErrorResponse;
import com.movie.waiting.dto.QueueStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "대기열 상태", description = "대기열 상태 조회 API")
public interface QueueStatusControllerDocs {

    @Operation(
            summary = "대기열 상태 조회",
            description = "토큰의 현재 상태, 순번, 다음 조회 권장 간격을 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "대기열 상태 조회 성공",
                    content = @Content(schema = @Schema(implementation = QueueStatusResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "상영 정보 또는 토큰을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    QueueStatusResponse status(
            @Parameter(description = "상영 ID", example = "screening-1", required = true) String screeningId,
            @Parameter(description = "대기열 토큰", example = "a8f2c1e0-9a34-4a6c-b8a8-f0f2fca38a11", required = true) String token
    );
}
