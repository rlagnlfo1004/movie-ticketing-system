package com.movie.reservation.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentContextCodecTest {

    private final PaymentContextCodec codec = new PaymentContextCodec(Base64.getEncoder()
            .encodeToString("12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8)));

    @Test
    void recoversOriginalSeatsAndTokenFromOpaquePaymentContext() {
        PaymentContextCodec.Context context = new PaymentContextCodec.Context(
                "1", "hold-123", "payment-123", "active-token-123", "idempotency-123",
                List.of("A-12", "A-13"), 30000, 1794128000000L);
        assertThat(codec.decode(codec.encode(context))).isEqualTo(context);
    }

    @Test
    void rejectsModifiedPaymentContext() {
        PaymentContextCodec.Context context = new PaymentContextCodec.Context(
                "1", "hold-123", "payment-123", "active-token-123", "idempotency-123",
                List.of("A-12"), 15000, 1794128000000L);
        String encoded = codec.encode(context);
        char replacement = encoded.charAt(10) == 'A' ? 'B' : 'A';
        String tampered = encoded.substring(0, 10) + replacement + encoded.substring(11);
        assertThatThrownBy(() -> codec.decode(tampered)).isInstanceOf(IllegalArgumentException.class);
    }
}
