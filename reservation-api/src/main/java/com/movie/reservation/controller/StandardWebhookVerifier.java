package com.movie.reservation.controller;

import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StandardWebhookVerifier {

    private final byte[] key;

    public StandardWebhookVerifier(@Value("${reservation.webhook-secret}") String secret) {
        if (!secret.startsWith("whsec_")) throw new IllegalArgumentException("Webhook secret must start with whsec_");
        this.key = Base64.getDecoder().decode(secret.substring(6));
    }

    public void verify(String body, String id, String timestamp, String signatures) {
        if (body == null || id == null || timestamp == null || signatures == null) throw invalid();
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException invalidTimestamp) {
            throw invalid();
        }
        if (Math.abs(Instant.now().getEpochSecond() - seconds) > 300) throw invalid();
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            expected = mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        } catch (Exception cryptoFailure) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", cryptoFailure);
        }
        for (String candidate : signatures.split(" ")) {
            if (!candidate.startsWith("v1,")) continue;
            try {
                if (MessageDigest.isEqual(expected, Base64.getDecoder().decode(candidate.substring(3)))) return;
            } catch (IllegalArgumentException ignored) {
                // Invalid base64 is not a valid signature.
            }
        }
        throw invalid();
    }

    private ReservationApiException invalid() {
        return new ReservationApiException(ReservationErrorCode.INVALID_WEBHOOK_SIGNATURE, null, null);
    }
}
