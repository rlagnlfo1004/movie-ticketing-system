package com.movie.storage.screening;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ScreeningSeedService {

    private final ScreeningRepository screeningRepository;

    @Transactional
    public Screening createOrReuse(String id, String movieTitle, java.time.Instant startsAt, int totalTicketCount) {
        return screeningRepository.findById(id)
                .orElseGet(() -> screeningRepository.save(new Screening(id, movieTitle, startsAt, totalTicketCount)));
    }

    @Transactional(readOnly = true)
    public boolean exists(String id) {
        return screeningRepository.existsById(id);
    }
}
