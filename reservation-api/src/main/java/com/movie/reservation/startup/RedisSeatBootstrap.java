package com.movie.reservation.startup;

import com.movie.reservation.repository.RedisSeatStateRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisSeatBootstrap implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final RedisSeatStateRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        Map<String, List<String>> seats = new HashMap<>();
        jdbc.query("SELECT screening_id,seat_no FROM reservation_seats", (RowCallbackHandler) rs ->
                seats.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>()).add(rs.getString(2)));
        seats.forEach(repository::seedReserved);
        jdbc.query("SELECT screening_id,queue_token,payment_id,reservation_id FROM reservations", (RowCallbackHandler) rs ->
                repository.seedCompleted(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
    }
}
