package com.movie.waiting.controller;

import com.movie.waiting.controller.docs.QueueStatusControllerDocs;
import com.movie.waiting.dto.QueueStatusResponse;
import com.movie.waiting.service.QueueStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/screenings/{screeningId}/queue")
@RequiredArgsConstructor
public class QueueStatusController implements QueueStatusControllerDocs {

    private final QueueStatusService queueStatusService;

    @GetMapping(path = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public QueueStatusResponse status(
            @PathVariable String screeningId,
            @RequestParam String token
    ) {
        return queueStatusService.getStatus(screeningId, token);
    }
}
