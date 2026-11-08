package com.movie.reservation.startup;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

@Component
@Order(1)
@RequiredArgsConstructor
public class SeatScreeningInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        jdbc.update("""
                INSERT IGNORE INTO screenings
                  (id, movie_title, starts_at, total_seat_count, remaining_seat_count, created_at)
                VALUES (?, ?, ?, 1000, 1000, ?)
                """, "1", "Movie Kim Demo Screening", Timestamp.from(Instant.parse("2026-05-12T10:00:00Z")),
                Timestamp.from(Instant.now()));

        List<String> seats = new ArrayList<>(1000);
        for (char row = 'A'; row <= 'Y'; row++) {
            for (int column = 1; column <= 40; column++) {
                seats.add(row + "-" + column);
            }
        }
        jdbc.batchUpdate("INSERT IGNORE INTO screening_seats (screening_id, seat_no, status) VALUES (?, ?, 'AVAILABLE')",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement statement, int index) throws java.sql.SQLException {
                        statement.setString(1, "1");
                        statement.setString(2, seats.get(index));
                    }

                    @Override
                    public int getBatchSize() {
                        return seats.size();
                    }
                });
    }
}
