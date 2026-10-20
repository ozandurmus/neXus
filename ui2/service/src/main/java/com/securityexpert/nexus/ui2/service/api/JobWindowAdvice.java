package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.Map;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;
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

    /**
     * PO 2026-10-20: an administrator's manual device job inside business hours without the acknowledgement. Nothing
     * was admitted; the client shows {@code message} and, on confirmation, repeats the same request with
     * {@code ack_header: true} (or {@code ?business_hours_ack=true}).
     */
    @ExceptionHandler(ManualAdmission.BusinessHoursConfirmRequired.class)
    public ResponseEntity<Map<String, String>> confirm(ManualAdmission.BusinessHoursConfirmRequired refusal) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", ManualAdmission.CONFIRM_CODE);
        body.put("message", refusal.getMessage());
        body.put("business_hours_start", refusal.hours().start().toString());
        body.put("business_hours_end", refusal.hours().end().toString());
        body.put("zone", JobWindowPolicy.ZONE);
        body.put("ack_header", ManualAdmission.ACK_HEADER);
        body.put("ack_parameter", ManualAdmission.ACK_PARAMETER);
        return ResponseEntity.status(409).body(body);
    }
}
