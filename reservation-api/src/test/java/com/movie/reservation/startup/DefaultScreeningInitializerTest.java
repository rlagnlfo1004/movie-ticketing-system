package com.movie.reservation.startup;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movie.reservation.config.ReservationProperties;
import com.movie.storage.screening.Screening;
import com.movie.storage.screening.ScreeningSeedService;
import com.movie.storage.ticket.TicketInventoryRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class DefaultScreeningInitializerTest {

    ScreeningSeedService screeningSeedService = org.mockito.Mockito.mock(ScreeningSeedService.class);
    TicketInventoryRepository ticketInventoryRepository = org.mockito.Mockito.mock(TicketInventoryRepository.class);

    @Test
    void seedsDefaultScreeningAndInitializesMissingInventory() throws Exception {
        Instant startsAt = Instant.parse("2026-05-12T10:00:00Z");
        ReservationProperties properties = new ReservationProperties(
                new ReservationProperties.ActiveAdmission("waiting:screening"),
                new ReservationProperties.Idempotency(Duration.ofMinutes(10)),
                "^[A-Za-z0-9._:-]{8,256}$",
                "^[A-Za-z0-9._:-]{8,128}$",
                new ReservationProperties.PersistencePending(3),
                new ReservationProperties.DefaultScreening("1", "Demo Movie", startsAt, 100)
        );
        when(screeningSeedService.createOrReuse("1", "Demo Movie", startsAt, 100))
                .thenReturn(new Screening("1", "Demo Movie", startsAt, 100));

        new DefaultScreeningInitializer(properties, screeningSeedService, ticketInventoryRepository)
                .run(new DefaultApplicationArguments());

        verify(screeningSeedService).createOrReuse("1", "Demo Movie", startsAt, 100);
        verify(ticketInventoryRepository).initializeIfAbsent("1", 100);
    }
}
