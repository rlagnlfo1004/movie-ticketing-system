package com.movie.reservation.startup;

import com.movie.reservation.config.ReservationProperties;
import com.movie.storage.screening.Screening;
import com.movie.storage.screening.ScreeningSeedService;
import com.movie.storage.ticket.TicketInventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultScreeningInitializer implements ApplicationRunner {

    private final ReservationProperties properties;
    private final ScreeningSeedService screeningSeedService;
    private final TicketInventoryRepository ticketInventoryRepository;

    @Override
    public void run(ApplicationArguments args) {
        ReservationProperties.DefaultScreening seed = properties.defaultScreening();
        Screening screening = screeningSeedService.createOrReuse(
                seed.id(),
                seed.movieTitle(),
                seed.startsAt(),
                seed.totalTicketCount()
        );
        ticketInventoryRepository.initializeIfAbsent(screening.getId(), screening.getTotalTicketCount());
    }
}
