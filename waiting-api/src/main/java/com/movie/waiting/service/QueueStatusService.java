package com.movie.waiting.service;

import com.movie.waiting.config.QueueStatusProperties;
import com.movie.waiting.domain.QueueStatus;
import com.movie.waiting.dto.QueueStatusResponse;
import com.movie.waiting.exception.InvalidQueueTokenException;
import com.movie.waiting.exception.InvalidScreeningException;
import com.movie.waiting.repository.RedisActiveAdmissionRepository;
import com.movie.waiting.repository.RedisQueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class QueueStatusService {

    private final ScreeningRegistry screeningRegistry;
    private final RedisActiveAdmissionRepository activeAdmissionRepository;
    private final RedisQueueRepository queueRepository;
    private final QueueStatusProperties queueStatusProperties;

    public QueueStatusResponse getStatus(String screeningId, String tokenInput) {
        if (!screeningRegistry.exists(screeningId)) {
            throw new InvalidScreeningException(screeningId);
        }
        if (!StringUtils.hasText(tokenInput)) {
            throw new InvalidQueueTokenException();
        }

        String token = tokenInput.trim();
        if (activeAdmissionRepository.exists(screeningId, token)) {
            return new QueueStatusResponse(
                    screeningId,
                    token,
                    QueueStatus.ACTIVE,
                    null,
                    null,
                    queueStatusProperties.getActivePollAfterMillis()
            );
        }

        Long zeroBasedRank = queueRepository.rank(screeningId, token);
        if (zeroBasedRank == null) {
            return new QueueStatusResponse(
                    screeningId,
                    token,
                    QueueStatus.NOT_FOUND,
                    null,
                    null,
                    queueStatusProperties.getNotFoundPollAfterMillis()
            );
        }

        return new QueueStatusResponse(
                screeningId,
                token,
                QueueStatus.WAITING,
                zeroBasedRank + 1,
                queueRepository.cardinality(screeningId),
                queueStatusProperties.getWaitingPollAfterMillis()
        );
    }
}
