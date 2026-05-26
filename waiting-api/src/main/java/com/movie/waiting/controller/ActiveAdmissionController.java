package com.movie.waiting.controller;

import com.movie.waiting.controller.docs.ActiveAdmissionControllerDocs;
import com.movie.waiting.dto.ActiveAdmissionResponse;
import com.movie.waiting.service.ActiveAdmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/screenings/{screeningId}/admissions")
@RequiredArgsConstructor
public class ActiveAdmissionController implements ActiveAdmissionControllerDocs {

    private final ActiveAdmissionService activeAdmissionService;

    @GetMapping(path = "/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ActiveAdmissionResponse admission(
            @PathVariable String screeningId,
            @PathVariable String token
    ) {
        return activeAdmissionService.getAdmission(screeningId, token);
    }
}
