package com.securityexpert.nexus.ui2.service.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.security.LoginFlow;
import com.securityexpert.nexus.ui2.service.security.SamlAuthenticationService;
import com.securityexpert.nexus.ui2.service.security.SamlSettingsService;

/**
 * The browser-facing SAML 2.0 routes (migration plan Step 4): SP-initiated sign-in ({@code GET /saml2/authenticate/adfs}), the
 * assertion consumer service ({@code POST /login/saml2/sso/adfs}, HTTP-POST binding) and the SP metadata for the ADFS relying
 * party trust ({@code GET /saml2/service-provider-metadata/adfs}). They run before any session exists, so like {@code POST /login}
 * they are explicitly ungated, and only while SAML is switched on: with SAML off they are unmapped, exactly as before.
 *
 * <p>A refused sign-in is a {@code 401} page that says why in plain words (a browser form post cannot show JSON). A success
 * ends in the same {@link LoginFlow} as every other mechanism: a new session cookie and a redirect to the application, or, when
 * the actor already has a session, a redirect carrying the takeover token that {@code POST /login/resolve} consumes.</p>
 */
@RestController
public final class SamlLoginController {

    private final SamlAuthenticationService saml;
    private final SamlSettingsService settings;
    private final LoginFlow loginFlow;
    private final SessionCookieWriter cookieWriter;
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

    private final int requestsPerMinute;

    public SamlLoginController(SamlAuthenticationService saml, SamlSettingsService settings, LoginFlow loginFlow,
            SessionCookieWriter cookieWriter,
            @org.springframework.beans.factory.annotation.Value("${ui2.saml.requests-per-minute:60}") int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
        this.saml = saml;
        this.settings = settings;
        this.loginFlow = loginFlow;
        this.cookieWriter = cookieWriter;
    }

    @GetMapping(SamlSettingsService.AUTHENTICATE_PATH)
    public ResponseEntity<String> authenticate(HttpServletRequest request) {
        if (!settings.isEnabled()) return ResponseEntity.notFound().build();
        if (!admit(request)) return page(HttpStatus.TOO_MANY_REQUESTS, "Too many sign-in attempts. Wait a minute and try again.");
        var started = saml.start(request);
        if (started.isEmpty()) return page(HttpStatus.SERVICE_UNAVAILABLE, "Single sign-on is not available right now. Try again in a minute.");
        if (started.get().redirectUrl() != null) {
            return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, started.get().redirectUrl())
                    .cacheControl(CacheControl.noStore()).build();
        }
        String form = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Signing in</title></head>"
                + "<body onload=\"document.forms[0].submit()\"><form method=\"post\" action=\"" + escape(started.get().postUrl())
                + "\"><input type=\"hidden\" name=\"SAMLRequest\" value=\"" + escape(started.get().samlRequest())
                + "\"><noscript><button type=\"submit\">Continue to ADFS</button></noscript></form></body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore()).body(form);
    }

    @PostMapping(value = SamlSettingsService.ACS_PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> assertionConsumer(HttpServletRequest request, HttpServletResponse response) {
        if (!settings.isEnabled()) return ResponseEntity.notFound().build();
        if (!admit(request)) return page(HttpStatus.TOO_MANY_REQUESTS, "Too many sign-in attempts. Wait a minute and try again.");
        SamlAuthenticationService.Result result = saml.consume(request.getParameter("SAMLResponse"));
        if (result instanceof SamlAuthenticationService.Result.Unavailable) return ResponseEntity.notFound().build();
        if (result instanceof SamlAuthenticationService.Result.Refused refused) {
            return page(HttpStatus.UNAUTHORIZED, refused.message(), refused.code());
        }
        var success = ((SamlAuthenticationService.Result.Authenticated) result).success();
        LoginFlow.LoginResult login = loginFlow.login(success, Instant.now());
        if (login instanceof LoginFlow.LoginResult.DirectoryUnavailable) {
            return page(HttpStatus.SERVICE_UNAVAILABLE, "Sign-in could not be completed right now. Try again in a minute.",
                    "SAML_UNAVAILABLE");
        }
        if (login instanceof LoginFlow.LoginResult.Conflict conflict) {
            return redirect("/?saml_conflict_token=" + URLEncoder.encode(conflict.conflictToken(), StandardCharsets.UTF_8));
        }
        cookieWriter.write(response, ((LoginFlow.LoginResult.NewSession) login).rawCookieValue());
        return redirect("/");
    }

    @GetMapping(value = SamlSettingsService.METADATA_PATH, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> metadata() {
        if (!settings.isEnabled()) return ResponseEntity.notFound().build();
        return saml.metadata().map(xml -> ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
                .cacheControl(CacheControl.noStore()).body(xml)).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<String> redirect(String location) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, location)
                .header("Referrer-Policy", "no-referrer").cacheControl(CacheControl.noStore()).build();
    }

    private static ResponseEntity<String> page(HttpStatus status, String message) {
        return page(status, message, null);
    }

    private static ResponseEntity<String> page(HttpStatus status, String message, String code) {
        String body = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Sign-in refused</title></head><body>"
                + "<h1>Single sign-on</h1><p role=\"alert\">" + escape(message) + "</p>"
                + (code == null ? "" : "<p>Reason: <code>" + escape(code) + "</code></p>")
                + "<p><a href=\"/\">Back to sign-in</a></p></body></html>";
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore()).body(body);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** A per-address ceiling on these unauthenticated routes: every request costs a signature check or a redirect. */
    private boolean admit(HttpServletRequest request) {
        long now = System.currentTimeMillis();
        if (windows.size() > 10_000) windows.clear();
        String source = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        long[] window = windows.compute(source, (key, current) -> {
            if (current == null || now - current[0] >= 60_000L) return new long[] { now, 1 };
            current[1]++;
            return current;
        });
        return window[1] <= requestsPerMinute;
    }
}
