package com.movie.reservation.controller;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movie.reservation.exception.ReservationApiExceptionHandler;
import com.movie.reservation.service.PaymentCompletionService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PortOneWebhookControllerTest {

    private static final byte[] KEY = "local-webhook-secret".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString(KEY);
    private final PaymentCompletionService completion = Mockito.mock(PaymentCompletionService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new PortOneWebhookController(new StandardWebhookVerifier(SECRET), new ObjectMapper(), completion))
            .setControllerAdvice(new ReservationApiExceptionHandler()).build();

    @Test
    void acceptsSignedPaidWebhook() throws Exception {
        String body = "{\"type\":\"Transaction.Paid\",\"timestamp\":\"2026-11-08T00:00:00Z\",\"data\":{\"paymentId\":\"pay-123\"}}";
        String id = "msg-123";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        String signature = "v1," + Base64.getEncoder().encodeToString(mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));

        mvc.perform(post("/api/v1/webhooks/portone").contentType("application/json")
                        .header("webhook-id", id).header("webhook-timestamp", timestamp)
                        .header("webhook-signature", signature).content(body))
                .andExpect(status().isOk());
        verify(completion).complete("pay-123");
    }

    @Test
    void rejectsTamperedWebhook() throws Exception {
        mvc.perform(post("/api/v1/webhooks/portone").contentType("application/json")
                        .header("webhook-id", "msg-123")
                        .header("webhook-timestamp", Long.toString(Instant.now().getEpochSecond()))
                        .header("webhook-signature", "v1,invalid")
                        .content("{\"type\":\"Transaction.Paid\"}"))
                .andExpect(status().isBadRequest());
        Mockito.verifyNoInteractions(completion);
    }
}
