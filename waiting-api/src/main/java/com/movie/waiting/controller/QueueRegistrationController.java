package com.movie.waiting.controller;

import com.movie.waiting.controller.docs.QueueRegistrationControllerDocs;
import com.movie.waiting.domain.QueueEntry;
import com.movie.waiting.dto.QueueRegistrationRequest;
import com.movie.waiting.dto.QueueRegistrationResponse;
import com.movie.waiting.service.QueueRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/screenings/{screeningId}/queue")
@RequiredArgsConstructor
public class QueueRegistrationController implements QueueRegistrationControllerDocs {

    private final QueueRegistrationService queueRegistrationService;

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public QueueRegistrationResponse register(
            @PathVariable String screeningId,
            @RequestBody(required = false) QueueRegistrationRequest request
    ) {
        String token = request == null ? null : request.token();
        QueueEntry entry = queueRegistrationService.register(screeningId, token);
        return new QueueRegistrationResponse(
                entry.screeningId(),
                entry.token(),
                entry.registered(),
                entry.newlyRegistered(),
                entry.score()
        );
    }
}
