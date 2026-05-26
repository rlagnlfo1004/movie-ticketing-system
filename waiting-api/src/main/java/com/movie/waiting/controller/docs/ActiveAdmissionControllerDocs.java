package com.movie.waiting.controller.docs;

import com.movie.waiting.dto.ActiveAdmissionResponse;
import com.movie.waiting.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "입장 가능 토큰", description = "입장 가능 토큰 상태 조회 API")
public interface ActiveAdmissionControllerDocs {

    @Operation(
            summary = "입장 토큰 상태 조회",
            description = "요청한 토큰이 현재 입장 가능한 상태인지 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "입장 토큰 상태 조회 성공",
                    content = @Content(schema = @Schema(implementation = ActiveAdmissionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "상영 정보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    ActiveAdmissionResponse admission(
            @Parameter(description = "상영 ID", example = "screening-1", required = true) String screeningId,
            @Parameter(description = "대기열 토큰", example = "a8f2c1e0-9a34-4a6c-b8a8-f0f2fca38a11", required = true) String token
    );
}
