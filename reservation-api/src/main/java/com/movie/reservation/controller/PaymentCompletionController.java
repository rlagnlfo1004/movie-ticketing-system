package com.movie.reservation.controller;

import com.movie.reservation.service.PaymentCompletionService;
import com.movie.reservation.service.PaymentCompletionService.CompletionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentCompletionController {

    private final PaymentCompletionService service;

    @PostMapping("/{paymentId}/complete")
    public CompletionResponse complete(@PathVariable String paymentId) {
        return service.complete(paymentId);
    }
}
