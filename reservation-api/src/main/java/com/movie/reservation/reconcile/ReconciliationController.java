package com.movie.reservation.reconcile;

import com.movie.reservation.reconcile.ReconciliationService.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/reconcile")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService service;

    @GetMapping
    public Result status() {
        return service.lastResult();
    }

    @PostMapping
    public Result run() {
        return service.forceReconcile();
    }
}
