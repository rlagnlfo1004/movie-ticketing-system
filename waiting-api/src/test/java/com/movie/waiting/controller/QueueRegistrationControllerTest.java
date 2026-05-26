package com.movie.waiting.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.movie.waiting.domain.QueueEntry;
import com.movie.waiting.exception.InvalidScreeningException;
import com.movie.waiting.exception.WaitingApiExceptionHandler;
import com.movie.waiting.service.QueueRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QueueRegistrationControllerTest {

    private final QueueRegistrationService service = Mockito.mock(QueueRegistrationService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new QueueRegistrationController(service))
                .setControllerAdvice(new WaitingApiExceptionHandler())
                .build();
    }

    @Test
    void registersWithoutProvidedToken() throws Exception {
        when(service.register("1", null))
                .thenReturn(new QueueEntry("1", "4bdf9bdc-8b1f-4660-ad3d-9c3adca9400d", 1000L, true, true));

        mockMvc.perform(post("/api/v1/screenings/1/queue/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningId", is("1")))
                .andExpect(jsonPath("$.token", is("4bdf9bdc-8b1f-4660-ad3d-9c3adca9400d")))
                .andExpect(jsonPath("$.registered", is(true)))
                .andExpect(jsonPath("$.newlyRegistered", is(true)))
                .andExpect(jsonPath("$.score", is(1000)));
    }

    @Test
    void registersWithProvidedToken() throws Exception {
        when(service.register("1", "client-token"))
                .thenReturn(new QueueEntry("1", "client-token", 1000L, true, true));

        mockMvc.perform(post("/api/v1/screenings/1/queue/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"client-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", is("client-token")))
                .andExpect(jsonPath("$.newlyRegistered", is(true)));
    }

    @Test
    void duplicateRegistrationReturnsNewlyRegisteredFalse() throws Exception {
        when(service.register("1", "client-token"))
                .thenReturn(new QueueEntry("1", "client-token", 500L, true, false));

        mockMvc.perform(post("/api/v1/screenings/1/queue/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"client-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", is("client-token")))
                .andExpect(jsonPath("$.newlyRegistered", is(false)))
                .andExpect(jsonPath("$.score", is(500)));
    }

    @Test
    void invalidScreeningReturnsErrorResponse() throws Exception {
        when(service.register(any(), any())).thenThrow(new InvalidScreeningException("missing"));

        mockMvc.perform(post("/api/v1/screenings/missing/queue/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_SCREENING")));
    }
}
