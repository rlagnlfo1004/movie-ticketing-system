package com.movie.reservation.controller.docs;

import com.movie.reservation.dto.ErrorResponse;
import com.movie.reservation.dto.TicketHistoryResponse;
import com.movie.reservation.dto.TicketIssuanceRequest;
import com.movie.reservation.dto.TicketIssuanceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;

@Tag(name = "티켓 발급", description = "티켓 발급 및 발급 이력 조회 API")
public interface TicketIssuanceControllerDocs {

    @Operation(
            summary = "티켓 발급",
            description = "Queue-Token, Idempotency-Key 헤더를 기반으로 상영 티켓을 발급합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "티켓 발급 성공",
                    content = @Content(schema = @Schema(implementation = TicketIssuanceResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "동일 Idempotency-Key로 이미 발급된 요청",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    TicketIssuanceResponse issue(
            @Parameter(description = "상영 ID", example = "screening-1", required = true) String screeningId,
            @Parameter(description = "Queue-Token 헤더", example = "a8f2c1e0-9a34-4a6c-b8a8-f0f2fca38a11") String queueToken,
            @Parameter(description = "Idempotency-Key 헤더", example = "issue-ticket-001") String idempotencyKey,
            @RequestBody(
                    description = "티켓 발급 요청 본문",
                    required = true,
                    content = @Content(schema = @Schema(implementation = TicketIssuanceRequest.class))
            ) TicketIssuanceRequest request
    );

    @Operation(
            summary = "티켓 발급 이력 조회",
            description = "상영의 티켓 발급 이력을 조회하고, queueToken으로 선택 필터링할 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "티켓 발급 이력 조회 성공",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = TicketHistoryResponse.class)))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "상영 정보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    List<TicketHistoryResponse> history(
            @Parameter(description = "상영 ID", example = "screening-1", required = true) String screeningId,
            @Parameter(description = "선택 대기열 토큰 필터", example = "a8f2c1e0-9a34-4a6c-b8a8-f0f2fca38a11") String queueToken
    );
}
