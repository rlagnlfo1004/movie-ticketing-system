package com.movie.waiting.service;

import com.movie.waiting.dto.ActiveAdmissionResponse;
import com.movie.waiting.dto.ActiveAdmissionResponse.Status;
import com.movie.waiting.exception.InvalidQueueTokenException;
import com.movie.waiting.exception.InvalidScreeningException;
import com.movie.waiting.repository.RedisActiveAdmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ActiveAdmissionService {

    private final ScreeningRegistry screeningRegistry;
    private final RedisActiveAdmissionRepository activeAdmissionRepository;

    public ActiveAdmissionResponse getAdmission(String screeningId, String tokenInput) {
        if (!screeningRegistry.exists(screeningId)) {
            throw new InvalidScreeningException(screeningId);
        }
        if (!StringUtils.hasText(tokenInput)) {
            throw new InvalidQueueTokenException();
        }

        String token = tokenInput.trim();
        Status status = activeAdmissionRepository.exists(screeningId, token) ? Status.ACTIVE : Status.NOT_FOUND;
        return new ActiveAdmissionResponse(screeningId, token, status);
    }
}
