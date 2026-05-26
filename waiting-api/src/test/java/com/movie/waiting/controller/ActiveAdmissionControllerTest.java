package com.movie.waiting.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.movie.waiting.dto.ActiveAdmissionResponse;
import com.movie.waiting.dto.ActiveAdmissionResponse.Status;
import com.movie.waiting.exception.InvalidQueueTokenException;
import com.movie.waiting.exception.InvalidScreeningException;
import com.movie.waiting.exception.WaitingApiExceptionHandler;
import com.movie.waiting.service.ActiveAdmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ActiveAdmissionControllerTest {

    private final ActiveAdmissionService service = Mockito.mock(ActiveAdmissionService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ActiveAdmissionController(service))
                .setControllerAdvice(new WaitingApiExceptionHandler())
                .build();
    }

    @Test
    void returnsActiveAdmissionResponseShape() throws Exception {
        when(service.getAdmission("1", "token-a"))
                .thenReturn(new ActiveAdmissionResponse("1", "token-a", Status.ACTIVE));

        mockMvc.perform(get("/api/v1/screenings/1/admissions/token-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningId", is("1")))
                .andExpect(jsonPath("$.token", is("token-a")))
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    void returnsNotFoundAdmissionStatus() throws Exception {
        when(service.getAdmission("1", "missing-token"))
                .thenReturn(new ActiveAdmissionResponse("1", "missing-token", Status.NOT_FOUND));

        mockMvc.perform(get("/api/v1/screenings/1/admissions/missing-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("NOT_FOUND")));
    }

    @Test
    void invalidScreeningUsesExistingErrorShape() throws Exception {
        when(service.getAdmission("2", "token-a"))
                .thenThrow(new InvalidScreeningException("2"));

        mockMvc.perform(get("/api/v1/screenings/2/admissions/token-a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_SCREENING")));
    }

    @Test
    void blankTokenUsesExistingErrorShape() throws Exception {
        when(service.getAdmission(eq("1"), anyString()))
                .thenThrow(new InvalidQueueTokenException());

        mockMvc.perform(get("/api/v1/screenings/1/admissions/%20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_TOKEN")));
    }
}
