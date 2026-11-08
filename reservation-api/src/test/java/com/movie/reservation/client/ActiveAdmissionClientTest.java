package com.movie.reservation.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.movie.reservation.config.ReservationProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ActiveAdmissionClientTest {

    @Test
    void checksAdmissionThroughWaitingApi() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/screenings/1/admissions/", exchange -> {
            boolean active = exchange.getRequestURI().getPath().endsWith("active-token-123");
            byte[] body = (active ? "{\"status\":\"ACTIVE\"}" : "{\"status\":\"NOT_FOUND\"}").getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ReservationProperties properties = new ReservationProperties(
                    new ReservationProperties.ActiveAdmission("http://127.0.0.1:" + server.getAddress().getPort()),
                    new ReservationProperties.Idempotency(Duration.ofMinutes(10)),
                    "^[A-Za-z0-9._:-]{8,256}$", "^[A-Za-z0-9._:-]{8,128}$");
            ActiveAdmissionClient client = new ActiveAdmissionClient(properties);
            assertThat(client.validate("1", "active-token-123").active()).isTrue();
            assertThat(client.validate("1", "waiting-token-123").active()).isFalse();
        } finally {
            server.stop(0);
        }
    }
}
