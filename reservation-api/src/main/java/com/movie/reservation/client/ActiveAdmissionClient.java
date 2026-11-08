package com.movie.reservation.client;

import com.movie.reservation.config.ReservationProperties;
import com.movie.reservation.client.ActiveAdmissionResult.Status;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ActiveAdmissionClient {

    private final RestClient client;

    public ActiveAdmissionClient(ReservationProperties properties) {
        this.client = RestClient.create(properties.activeAdmission().baseUrl());
    }

    private record Admission(String status) {
    }

    public ActiveAdmissionResult validate(String screeningId, String queueToken) {
        try {
            Admission result = client.get()
                    .uri("/api/v1/screenings/{sid}/admissions/{token}", screeningId, queueToken)
                    .retrieve().body(Admission.class);
            return new ActiveAdmissionResult(result != null && "ACTIVE".equals(result.status())
                    ? Status.ACTIVE : Status.NOT_ACTIVE);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                return new ActiveAdmissionResult(Status.NOT_ACTIVE);
            }
            throw exception;
        }
    }
}
