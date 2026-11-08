package com.movie.reservation.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class IdempotencyFingerprintTest {

    JsonCanonicalizer canonicalizer = new JsonCanonicalizer(new ObjectMapper());
    IdempotencyFingerprint fingerprint = new IdempotencyFingerprint(canonicalizer);

    @Test
    void jsonFieldOrderAndWhitespaceDoNotChangeFingerprint() {
        HttpServletRequest request = request("/api/v1/screenings/1/holds");

        String first = fingerprint.build(
                request,
                " active-token-123 ",
                "{\"seats\":[\"A-12\"],\"metadata\":{\"b\":2,\"a\":1}}".getBytes(StandardCharsets.UTF_8)
        );
        String second = fingerprint.build(
                request,
                "active-token-123",
                "{\n  \"metadata\": {\"a\": 1, \"b\": 2}, \"seats\": [\"A-12\"]\n}".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(first).isEqualTo(second);
    }

    @Test
    void queueTokenAndUriArePartOfFingerprint() {
        byte[] body = "{\"seats\":[\"A-12\"]}".getBytes(StandardCharsets.UTF_8);

        String first = fingerprint.build(request("/api/v1/screenings/1/holds"), "active-token-123", body);
        String differentToken = fingerprint.build(request("/api/v1/screenings/1/holds"), "other-token-123", body);
        String differentUri = fingerprint.build(request("/api/v1/screenings/2/holds"), "active-token-123", body);

        assertThat(first).isNotEqualTo(differentToken);
        assertThat(first).isNotEqualTo(differentUri);
    }

    private HttpServletRequest request(String uri) {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }
}
