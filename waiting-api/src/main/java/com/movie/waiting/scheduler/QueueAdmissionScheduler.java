package com.movie.waiting.scheduler;

import com.movie.waiting.config.QueueAdmissionProperties;
import com.movie.waiting.service.QueueAdmissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueAdmissionScheduler {

    private final QueueAdmissionProperties properties;
    private final QueueAdmissionService admissionService;

    @Scheduled(fixedDelayString = "${waiting.queue.admission.scheduler-interval:5000}")
    public void run() {
        for (String screeningId : properties.getScreeningIds()) {
            try {
                admissionService.admit(screeningId);
            } catch (RuntimeException exception) {
                log.error("Queue admission scheduler failed screeningId={}", screeningId, exception);
            }
        }
    }
}
