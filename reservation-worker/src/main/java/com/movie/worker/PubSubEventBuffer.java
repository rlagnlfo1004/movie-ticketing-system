package com.movie.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PubSubEventBuffer implements MessageListener {

    private final ReservationBatchWriter writer;
    private final LinkedBlockingQueue<ReservationEvent> queue = new LinkedBlockingQueue<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            queue.add(mapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), ReservationEvent.class));
        } catch (Exception exception) {
            log.error("Invalid reservation event", exception);
        }
    }

    @Scheduled(fixedDelay = 50)
    public void flush() {
        List<ReservationEvent> batch = new ArrayList<>(100);
        queue.drainTo(batch, 100);
        if (batch.isEmpty()) return;
        try {
            writer.persist(batch);
        } catch (Exception exception) {
            queue.addAll(batch);
            log.error("Reservation batch failed; retained in worker memory", exception);
        }
    }
}
