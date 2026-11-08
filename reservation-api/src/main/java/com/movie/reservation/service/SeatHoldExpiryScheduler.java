package com.movie.reservation.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SeatHoldExpiryScheduler {

    private final JdbcTemplate jdbc;
    private final SeatHoldExpiryService service;

    @Scheduled(fixedDelay = 1000)
    public void run() {
        List<String> expired = jdbc.query("""
                SELECT hold_id FROM seat_holds
                WHERE status='HELD' AND held_until<NOW(6)
                ORDER BY held_until LIMIT 500
                """, (rs, row) -> rs.getString(1));
        for (String holdId : expired) service.expire(holdId);
    }
}
