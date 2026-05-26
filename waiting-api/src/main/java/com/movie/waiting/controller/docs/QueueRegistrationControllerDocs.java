package com.movie.waiting.controller.docs;

import com.movie.waiting.dto.ErrorResponse;
import com.movie.waiting.dto.QueueRegistrationRequest;
import com.movie.waiting.dto.QueueRegistrationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "대기열 등록", description = "대기열 등록 API")
public interface QueueRegistrationControllerDocs {

    @Operation(
            summary = "대기열 등록",
            description = "토큰이 있으면 재사용하고, 없으면 새 토큰을 발급하여 대기열에 등록합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "대기열 등록 성공",
                    content = @Content(schema = @Schema(implementation = QueueRegistrationResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "상영 정보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    QueueRegistrationResponse register(
            @Parameter(description = "상영 ID", example = "screening-1", required = true) String screeningId,
            @RequestBody(
                    description = "선택 요청 본문입니다. token이 없으면 새 토큰을 발급합니다.",
                    required = false,
                    content = @Content(schema = @Schema(implementation = QueueRegistrationRequest.class))
            ) QueueRegistrationRequest request
    );
}
