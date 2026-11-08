package com.movie.reservation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import com.movie.reservation.service.PaymentCompletionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PortOneWebhookController {

    private final StandardWebhookVerifier verifier;
    private final ObjectMapper mapper;
    private final PaymentCompletionService completion;

    @PostMapping(path = "/api/v1/webhooks/portone", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void receive(@RequestBody String rawBody,
                        @RequestHeader(value = "webhook-id", required = false) String id,
                        @RequestHeader(value = "webhook-timestamp", required = false) String timestamp,
                        @RequestHeader(value = "webhook-signature", required = false) String signature) {
        verifier.verify(rawBody, id, timestamp, signature);
        JsonNode event;
        try {
            event = mapper.readTree(rawBody);
        } catch (Exception invalidBody) {
            throw new ReservationApiException(ReservationErrorCode.INVALID_WEBHOOK_SIGNATURE, null, null);
        }
        if (!"Transaction.Paid".equals(event.path("type").asText())) return;
        String paymentId = event.path("data").path("paymentId").asText();
        if (paymentId.isBlank()) {
            throw new ReservationApiException(ReservationErrorCode.INVALID_WEBHOOK_SIGNATURE, null, null);
        }
        completion.complete(paymentId);
    }
}
