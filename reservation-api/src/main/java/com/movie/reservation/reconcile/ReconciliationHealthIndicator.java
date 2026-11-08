package com.movie.reservation.reconcile;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("reconciliation")
@RequiredArgsConstructor
public class ReconciliationHealthIndicator implements HealthIndicator {

    private final ReconciliationService service;

    @Override
    public Health health() {
        return service.isReady() ? Health.up().build() : Health.down().build();
    }
}
