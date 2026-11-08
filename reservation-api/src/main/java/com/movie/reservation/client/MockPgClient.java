package com.movie.reservation.client;

import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class MockPgClient {

    private final RestClient client;
    private final String baseUrl;

    public MockPgClient(@Value("${reservation.mock-pg-base-url}") String baseUrl,
                        @Value("${reservation.mock-pg-api-secret}") String apiSecret) {
        this.baseUrl = baseUrl;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "PortOne " + apiSecret)
                .build();
    }

    public record Amount(int total) {
    }

    public record Payment(String id, String status, Amount amount, String transactionId, String customData) {
    }

    private record PaymentList(List<Payment> items) {
    }

    public Payment find(String paymentId) {
        try {
            Payment payment = client.get().uri("/payments/{id}", paymentId).retrieve().body(Payment.class);
            if (payment == null) throw new IllegalStateException("Empty PG response");
            return payment;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new ReservationApiException(ReservationErrorCode.PAYMENT_NOT_PAID, null, null);
            }
            throw exception;
        }
    }

    public void preRegister(String paymentId, int amount) {
        client.post().uri("/payments/{id}/pre-register", paymentId)
                .body(Map.of("totalAmount", amount))
                .retrieve().toBodilessEntity();
    }

    public List<Payment> listPayments() {
        List<Payment> all = new ArrayList<>();
        for (int number = 0; number < 600; number++) {
            int page = number;
            String requestBody = "{\"page\":{\"number\":" + page + ",\"size\":100}}";
            URI uri = URI.create(baseUrl + "/payments?requestBody="
                    + URLEncoder.encode(requestBody, StandardCharsets.UTF_8));
            PaymentList result = client.get().uri(uri).retrieve().body(PaymentList.class);
            if (result == null || result.items() == null) throw new IllegalStateException("Empty PG payment page");
            all.addAll(result.items());
            if (result.items().size() < 100) return all;
        }
        throw new IllegalStateException("PG payment page limit exceeded");
    }

    public void cancel(String paymentId, String reason) {
        try {
            client.post().uri("/payments/{id}/cancel", paymentId)
                    .body(Map.of("reason", reason))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 409
                    || !exception.getResponseBodyAsString().contains("PAYMENT_ALREADY_CANCELLED")) {
                throw exception;
            }
        }
    }
}
