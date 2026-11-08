package com.movie.reservation.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SeatHoldExpiryService {

    private final JdbcTemplate jdbc;

    @Transactional
    public void expire(String holdId) {
        List<String> screeningIds = jdbc.query("""
                SELECT screening_id FROM seat_holds
                WHERE hold_id=? AND status='HELD' AND held_until<? FOR UPDATE
                """, (rs, row) -> rs.getString(1), holdId, Timestamp.from(Instant.now()));
        if (screeningIds.isEmpty()) return;
        String screeningId = screeningIds.getFirst();
        int released = jdbc.update("""
                UPDATE screening_seats SET status='AVAILABLE',hold_id=NULL,held_until=NULL
                WHERE hold_id=? AND status='HELD'
                """, holdId);
        if (released > 0) {
            jdbc.update("UPDATE screenings SET remaining_seat_count=remaining_seat_count+? WHERE id=?",
                    released, screeningId);
        }
        jdbc.update("UPDATE seat_holds SET status='EXPIRED' WHERE hold_id=?", holdId);
    }
}
