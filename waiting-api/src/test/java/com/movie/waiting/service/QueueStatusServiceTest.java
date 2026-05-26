package com.movie.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movie.waiting.config.QueueRegistrationProperties;
import com.movie.waiting.config.QueueStatusProperties;
import com.movie.waiting.domain.QueueStatus;
import com.movie.waiting.dto.QueueStatusResponse;
import com.movie.waiting.exception.InvalidQueueTokenException;
import com.movie.waiting.exception.InvalidScreeningException;
import com.movie.waiting.repository.RedisActiveAdmissionRepository;
import com.movie.waiting.repository.RedisQueueRepository;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class QueueStatusServiceTest {

    private final RedisActiveAdmissionRepository activeAdmissionRepository = Mockito.mock(RedisActiveAdmissionRepository.class);
    private final RedisQueueRepository queueRepository = Mockito.mock(RedisQueueRepository.class);
    private QueueStatusService service;

    @BeforeEach
    void setUp() {
        QueueStatusProperties properties = new QueueStatusProperties();
        properties.setWaitingPollAfterMillis(1500L);
        properties.setActivePollAfterMillis(200L);
        properties.setNotFoundPollAfterMillis(3500L);
        service = new QueueStatusService(
                registry("1"),
                activeAdmissionRepository,
                queueRepository,
                properties
        );
    }

    @Test
    void returnsWaitingRankAndWaitingCount() {
        when(activeAdmissionRepository.exists("1", "token-b")).thenReturn(false);
        when(queueRepository.rank("1", "token-b")).thenReturn(1L);
        when(queueRepository.cardinality("1")).thenReturn(3L);

        QueueStatusResponse response = service.getStatus("1", " token-b ");

        assertThat(response.screeningId()).isEqualTo("1");
        assertThat(response.token()).isEqualTo("token-b");
        assertThat(response.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(response.rank()).isEqualTo(2L);
        assertThat(response.waitingCount()).isEqualTo(3L);
        assertThat(response.pollAfterMillis()).isEqualTo(1500L);
    }

    @Test
    void returnsActiveBeforeWaitingRankLookup() {
        when(activeAdmissionRepository.exists("1", "token-a")).thenReturn(true);

        QueueStatusResponse response = service.getStatus("1", "token-a");

        assertThat(response.status()).isEqualTo(QueueStatus.ACTIVE);
        assertThat(response.rank()).isNull();
        assertThat(response.waitingCount()).isNull();
        assertThat(response.pollAfterMillis()).isEqualTo(200L);
        verify(queueRepository, never()).rank("1", "token-a");
        verify(queueRepository, never()).cardinality("1");
    }

    @Test
    void returnsNotFoundForUnknownToken() {
        when(activeAdmissionRepository.exists("1", "missing-token")).thenReturn(false);
        when(queueRepository.rank("1", "missing-token")).thenReturn(null);

        QueueStatusResponse response = service.getStatus("1", "missing-token");

        assertThat(response.status()).isEqualTo(QueueStatus.NOT_FOUND);
        assertThat(response.rank()).isNull();
        assertThat(response.waitingCount()).isNull();
        assertThat(response.pollAfterMillis()).isEqualTo(3500L);
    }

    @Test
    void expiredActiveTokenCollapsesToNotFoundWhenNoWaitingRankExists() {
        when(activeAdmissionRepository.exists("1", "expired-token")).thenReturn(false);
        when(queueRepository.rank("1", "expired-token")).thenReturn(null);

        QueueStatusResponse response = service.getStatus("1", "expired-token");

        assertThat(response.status()).isEqualTo(QueueStatus.NOT_FOUND);
        assertThat(response.pollAfterMillis()).isEqualTo(3500L);
    }

    @Test
    void rejectsBlankTokenBeforeRedisLookup() {
        assertThatThrownBy(() -> service.getStatus("1", " "))
                .isInstanceOf(InvalidQueueTokenException.class);
        verify(activeAdmissionRepository, never()).exists(Mockito.anyString(), Mockito.anyString());
        verify(queueRepository, never()).rank(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void rejectsInvalidScreeningBeforeRedisLookup() {
        assertThatThrownBy(() -> service.getStatus("missing", "token-a"))
                .isInstanceOf(InvalidScreeningException.class);
        verify(activeAdmissionRepository, never()).exists(Mockito.anyString(), Mockito.anyString());
        verify(queueRepository, never()).rank(Mockito.anyString(), Mockito.anyString());
    }

    private ScreeningRegistry registry(String... screeningIds) {
        QueueRegistrationProperties properties = new QueueRegistrationProperties();
        properties.setValidScreeningIds(Set.of(screeningIds));
        return new ScreeningRegistry(properties);
    }
}
