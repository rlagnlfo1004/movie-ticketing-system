package com.movie.reservation.client;

import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class MockPgClient {

    private final RestClient client;

    public MockPgClient(@Value("${reservation.mock-pg-base-url}") String baseUrl,
                        @Value("${reservation.mock-pg-api-secret}") String apiSecret) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "PortOne " + apiSecret)
                .build();
    }

    public record Amount(int total) {
    }

    public record Payment(String id, String status, Amount amount, String transactionId) {
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
}
