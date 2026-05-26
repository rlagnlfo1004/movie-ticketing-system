package com.movie.reservation.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IdempotencyFingerprint {

    private final JsonCanonicalizer jsonCanonicalizer;

    public String build(HttpServletRequest request, String queueToken, byte[] body) {
        String canonicalBody = jsonCanonicalizer.canonicalize(body);
        String bodyHash = sha256(canonicalBody);
        String source = request.getMethod()
                + "\n" + request.getRequestURI()
                + "\n" + queueToken.trim()
                + "\n" + bodyHash;
        return sha256(source);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
