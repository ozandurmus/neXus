package com.securityexpert.nexus.ui2.service.api;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.securityexpert.nexus.ui2.platform.CredentialStorePort;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialView;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * 2026-09-14 PO decision record section 3 (CS-1..CS-5) credential store
 * administration: four resources, body-only (no path variable, mirroring
 * {@code /local-identities}' own shape). Gated by
 * {@link GateChainInterceptor} -- reached only after {@code E1}-{@code E4}
 * required {@code role:security_admin}; this class performs no
 * authorization check of its own.
 *
 * <p>A stored secret is never a field on any request or response type this
 * class reads or writes back -- {@link CreateRequest#secret()} and
 * {@link ReplaceSecretRequest#secret()} are the only two places a secret
 * ever appears, and both are zeroed in a {@code finally} block once the
 * domain call returns.</p>
 */
@RestController
public final class CredentialAdministrationController {

    public record CreateRequest(
            @JsonProperty("display_name") String displayName,
            @JsonProperty("kind") String kind,
            @JsonProperty("username") String username,
            @JsonProperty("allows_check_point") boolean allowsCheckPoint,
            @JsonProperty("allows_palo_alto") boolean allowsPaloAlto,
            @JsonProperty("secret") char[] secret,
            @JsonProperty("passphrase") char[] passphrase) {
    }

    public record ReplaceSecretRequest(
            @JsonProperty("credential_id") String credentialId,
            @JsonProperty("secret") char[] secret,
            @JsonProperty("passphrase") char[] passphrase) {
    }

    public record CredentialRequest(@JsonProperty("credential_id") String credentialId) {
    }

    private final CredentialStorePort credentialStore;

    public CredentialAdministrationController(CredentialStorePort credentialStore) {
        this.credentialStore = credentialStore;
    }

    @PostMapping("/credentials")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request,
            HttpServletRequest servletRequest) {
        String actorFingerprint = actingAdmin(servletRequest);
        char[] secret = request.secret();
        char[] passphrase = request.passphrase();
        try {
            CredentialKind kind;
            try {
                kind = CredentialKind.fromWireValue(request.kind());
            } catch (IllegalArgumentException e) {
                return badRequest("INVALID_CREDENTIAL_KIND");
            }
            CredentialView view = credentialStore.create(actorFingerprint, request.displayName(), kind,
                    request.username(), request.allowsCheckPoint(), request.allowsPaloAlto(), secret,
                    optionalOf(passphrase));
            return ResponseEntity.ok(toBody(view));
        } finally {
            zero(secret);
            zero(passphrase);
        }
    }

    @GetMapping("/credentials")
    public ResponseEntity<Map<String, Object>> list() {
        List<Map<String, Object>> credentials =
                credentialStore.list().stream().map(CredentialAdministrationController::toBody).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("credentials", credentials);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/credentials/replace-secret")
    public ResponseEntity<Map<String, Object>> replaceSecret(@RequestBody ReplaceSecretRequest request,
            HttpServletRequest servletRequest) {
        String actorFingerprint = actingAdmin(servletRequest);
        char[] secret = request.secret();
        char[] passphrase = request.passphrase();
        try {
            CredentialStorePort.ReplaceSecretResult result = credentialStore.replaceSecret(actorFingerprint,
                    request.credentialId(), secret, optionalOf(passphrase));
            if (result instanceof CredentialStorePort.ReplaceSecretResult.NotFound) {
                return notFound();
            }
            if (result instanceof CredentialStorePort.ReplaceSecretResult.PassphraseNotAllowed) {
                return badRequest("PASSPHRASE_NOT_ALLOWED_FOR_CREDENTIAL_KIND");
            }
            CredentialStorePort.ReplaceSecretResult.Ok ok = (CredentialStorePort.ReplaceSecretResult.Ok) result;
            return ResponseEntity.ok(toBody(ok.view()));
        } finally {
            zero(secret);
            zero(passphrase);
        }
    }

    @PostMapping("/credentials/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody CredentialRequest request,
            HttpServletRequest servletRequest) {
        CredentialStorePort.DeleteResult result =
                credentialStore.delete(actingAdmin(servletRequest), request.credentialId());
        if (result instanceof CredentialStorePort.DeleteResult.NotFound) {
            return notFound();
        }
        if (result instanceof CredentialStorePort.DeleteResult.CredentialInUse) {
            // CS-4: distinct, non-identity-bearing -- names no device.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "CREDENTIAL_IN_USE");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("credential_id", request.credentialId());
        body.put("deleted", true);
        return ResponseEntity.ok(body);
    }

    private static Optional<char[]> optionalOf(char[] value) {
        return value == null || value.length == 0 ? Optional.empty() : Optional.of(value);
    }

    private static void zero(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private static String actingAdmin(HttpServletRequest servletRequest) {
        return (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "CREDENTIAL_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** CS-1/CS-3 section 3: exactly these fields -- never an encrypted secret, an encrypted passphrase, or the envelope key id. */
    private static Map<String, Object> toBody(CredentialView view) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("credential_id", view.credentialId());
        body.put("credential_reference_id", view.credentialReferenceId());
        body.put("display_name", view.displayName());
        body.put("kind", view.kind().wireValue());
        body.put("username", view.username());
        body.put("allows_check_point", view.allowsCheckPoint());
        body.put("allows_palo_alto", view.allowsPaloAlto());
        body.put("created_at", view.createdAt().toString());
        body.put("secret_set_at", view.secretSetAt().toString());
        return body;
    }
}
