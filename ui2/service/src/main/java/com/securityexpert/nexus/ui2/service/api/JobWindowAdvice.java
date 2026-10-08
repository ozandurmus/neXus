package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class JobWindowAdvice {
    @ExceptionHandler(JobWindowPolicy.OutsideWindow.class)
    public ResponseEntity<Map<String, String>> refused(JobWindowPolicy.OutsideWindow refusal) {
        return ResponseEntity.status(409).body(Map.of("code", JobWindowPolicy.CODE,
                "message", refusal.getMessage(), "next_window_start", refusal.nextWindowStart().toString()));
    }
}
