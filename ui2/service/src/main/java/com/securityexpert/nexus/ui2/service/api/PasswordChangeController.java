package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.security.PasswordChangeService;

/**
 * {@code POST /local-credentials/change-password} (C3A contract §4):
 * "callable by the identity itself (current password re-verified first,
 * exactly as a login attempt is)". Re-verifying the current password
 * inline <b>is</b> this endpoint's authorization -- no session or gate
 * chain is required, matching the contract's own wording. The
 * {@code role:security_admin}-administers-another-identity variant §4 also
 * describes is not implemented by this movement (see SESSION_CLOSE); only
 * the self-service path is.
 */
@RestController
public final class PasswordChangeController {

    public record ChangePasswordRequest(String username, char[] currentPassword, char[] newPassword) {
    }

    private final PasswordChangeService passwordChangeService;

    public PasswordChangeController(PasswordChangeService passwordChangeService) {
        this.passwordChangeService = passwordChangeService;
    }

    @PostMapping("/local-credentials/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody ChangePasswordRequest request) {
        PasswordChangeService.Result result = passwordChangeService.changePassword(
                request.username(), request.currentPassword(), request.newPassword());

        if (result instanceof PasswordChangeService.Result.Ok) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            return ResponseEntity.ok(body);
        }
        if (result instanceof PasswordChangeService.Result.PolicyViolation violation) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "PASSWORD_POLICY_VIOLATION");
            body.put("reason_code", violation.violationCode());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        // Result.InvalidCredentials -- the same generic surface a login
        // refusal uses (§5.3's spirit); the current password was wrong or
        // the identity is unknown.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "INVALID_CREDENTIALS");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }
}
