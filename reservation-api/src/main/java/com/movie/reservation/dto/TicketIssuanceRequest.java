package com.movie.reservation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "티켓 발급 요청")
public record TicketIssuanceRequest(
        @NotNull
        @Schema(description = "발급할 티켓 수량", example = "2", minimum = "1")
        Integer quantity
) {
}
