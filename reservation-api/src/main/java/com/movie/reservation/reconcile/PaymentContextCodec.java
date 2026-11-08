package com.movie.reservation.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaymentContextCodec {

    private static final int NONCE_SIZE = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper mapper = new ObjectMapper();

    public record Context(String screeningId, String holdId, String paymentId,
                          String queueToken, String idempotencyKey, List<String> seats,
                          int amount, long heldUntilMillis) {
    }

    public PaymentContextCodec(@Value("${reservation.reconciliation-key}") String encodedKey) {
        byte[] bytes = Base64.getDecoder().decode(encodedKey);
        if (bytes.length != 32) throw new IllegalArgumentException("Reconciliation key must be 32 bytes");
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public String encode(Context context) {
        try {
            byte[] nonce = new byte[NONCE_SIZE];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(mapper.writeValueAsBytes(context));
            byte[] result = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(result);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode payment context", exception);
        }
    }

    public Context decode(String value) {
        try {
            byte[] input = Base64.getUrlDecoder().decode(value);
            if (input.length <= NONCE_SIZE + 16) throw new IllegalArgumentException("Context is too short");
            byte[] nonce = Arrays.copyOfRange(input, 0, NONCE_SIZE);
            byte[] encrypted = Arrays.copyOfRange(input, NONCE_SIZE, input.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return mapper.readValue(cipher.doFinal(encrypted), Context.class);
        } catch (GeneralSecurityException | IllegalArgumentException | java.io.IOException invalid) {
            throw new IllegalArgumentException("Invalid payment context", invalid);
        }
    }
}
