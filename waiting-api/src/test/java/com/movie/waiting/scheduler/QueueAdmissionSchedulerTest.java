package com.movie.waiting.scheduler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.movie.waiting.config.QueueAdmissionProperties;
import com.movie.waiting.service.QueueAdmissionService;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class QueueAdmissionSchedulerTest {

    private final QueueAdmissionService service = Mockito.mock(QueueAdmissionService.class);

    @Test
    void delegatesOneConfiguredScreeningBatch() {
        QueueAdmissionScheduler scheduler = new QueueAdmissionScheduler(properties("1"), service);

        scheduler.run();

        verify(service).admit("1");
    }

    @Test
    void exceptionsDoNotBlockLaterScheduledScreenings() {
        QueueAdmissionScheduler scheduler = new QueueAdmissionScheduler(properties("1", "2"), service);
        doThrow(new IllegalStateException("boom")).when(service).admit("1");

        scheduler.run();

        verify(service).admit("1");
        verify(service).admit("2");
    }

    @Test
    void concurrentSchedulerInvocationsDelegateButServiceLockControlsMutation() {
        QueueAdmissionScheduler scheduler = new QueueAdmissionScheduler(properties("1"), service);

        scheduler.run();
        scheduler.run();

        verify(service, times(2)).admit("1");
    }

    private QueueAdmissionProperties properties(String... screeningIds) {
        QueueAdmissionProperties properties = new QueueAdmissionProperties();
        properties.setScreeningIds(new LinkedHashSet<>(Set.of(screeningIds)));
        return properties;
    }
}
