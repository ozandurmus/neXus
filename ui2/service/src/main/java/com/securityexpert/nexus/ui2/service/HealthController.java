package com.securityexpert.nexus.ui2.service;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The service role's health endpoint (contract §7: "exposes only the
 * service health endpoint"). Session/RBAC interceptors and read models
 * are added by later B1-x slices; this class seeds the {@code .service}
 * package root for B1-1.
 */
@RestController
public final class HealthController {

    @GetMapping("/healthz")
    public String health() {
        return "ok";
    }
}
