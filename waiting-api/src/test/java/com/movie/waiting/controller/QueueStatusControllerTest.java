package com.movie.waiting.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.movie.waiting.domain.QueueStatus;
import com.movie.waiting.dto.QueueStatusResponse;
import com.movie.waiting.exception.InvalidQueueTokenException;
import com.movie.waiting.exception.WaitingApiExceptionHandler;
import com.movie.waiting.service.QueueStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QueueStatusControllerTest {

    private final QueueStatusService service = Mockito.mock(QueueStatusService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new QueueStatusController(service))
                .setControllerAdvice(new WaitingApiExceptionHandler())
                .build();
    }

    @Test
    void returnsWaitingResponseShape() throws Exception {
        when(service.getStatus("1", "token-b"))
                .thenReturn(new QueueStatusResponse("1", "token-b", QueueStatus.WAITING, 2L, 3L, 1000L));

        mockMvc.perform(get("/api/v1/screenings/1/queue/status").param("token", "token-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningId", is("1")))
                .andExpect(jsonPath("$.token", is("token-b")))
                .andExpect(jsonPath("$.status", is("WAITING")))
                .andExpect(jsonPath("$.rank", is(2)))
                .andExpect(jsonPath("$.waitingCount", is(3)))
                .andExpect(jsonPath("$.pollAfterMillis", is(1000)));
    }

    @Test
    void activeResponseOmitsRankAndWaitingCount() throws Exception {
        when(service.getStatus("1", "token-a"))
                .thenReturn(new QueueStatusResponse("1", "token-a", QueueStatus.ACTIVE, null, null, 100L));

        mockMvc.perform(get("/api/v1/screenings/1/queue/status").param("token", "token-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.rank").doesNotExist())
                .andExpect(jsonPath("$.waitingCount").doesNotExist())
                .andExpect(jsonPath("$.pollAfterMillis", is(100)));
    }

    @Test
    void notFoundResponseOmitsRankAndWaitingCount() throws Exception {
        when(service.getStatus("1", "missing-token"))
                .thenReturn(new QueueStatusResponse("1", "missing-token", QueueStatus.NOT_FOUND, null, null, 3000L));

        mockMvc.perform(get("/api/v1/screenings/1/queue/status").param("token", "missing-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("NOT_FOUND")))
                .andExpect(jsonPath("$.rank").doesNotExist())
                .andExpect(jsonPath("$.waitingCount").doesNotExist())
                .andExpect(jsonPath("$.pollAfterMillis", is(3000)));
    }

    @Test
    void blankTokenReturnsBadRequest() throws Exception {
        when(service.getStatus("1", " "))
                .thenThrow(new InvalidQueueTokenException());

        mockMvc.perform(get("/api/v1/screenings/1/queue/status").param("token", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_TOKEN")));
    }
}
