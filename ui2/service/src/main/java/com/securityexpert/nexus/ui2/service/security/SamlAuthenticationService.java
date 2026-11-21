package com.securityexpert.nexus.ui2.service.security;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;

import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.schema.XSAny;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.saml.saml2.assertion.SAML2AssertionValidationParameters;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken;
import org.springframework.security.saml2.provider.service.authentication.Saml2PostAuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.Saml2RedirectAuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.metadata.OpenSaml5MetadataResolver;
import org.springframework.security.saml2.provider.service.registration.InMemoryRelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.security.saml2.provider.service.web.authentication.OpenSaml5AuthenticationRequestResolver;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import com.securityexpert.nexus.ui2.persistence.identity.SamlSettingsRecord;
import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.DirectoryObservation;
import com.securityexpert.nexus.ui2.platform.PrincipalFingerprint;

/**
 * SAML 2.0 service provider for ADFS (migration plan Step 4). It uses Spring Security's OpenSAML 5 provider as a library:
 * response and assertion signature, issuer, destination, recipient, audience, time conditions (with the configured clock
 * skew bound) and InResponseTo are all checked there. This class adds what the plan fixes on top:
 * <ul>
 *   <li>only <em>solicited</em> sign-in: a response must answer an AuthnRequest this service sent (kept in memory, five
 *       minutes, consumed once), so a captured response cannot be replayed, and an assertion id is accepted once;</li>
 *   <li>the actor is {@code PrincipalFingerprint.of("saml:adfs:" + normalized NameID)} (D2), never merged with LDAP;</li>
 *   <li>the group claim (D1, configurable) is reduced to the two configured group values; no mapped group means no sign-in;</li>
 *   <li>the proof of the group set ends at the assertion's {@code SessionNotOnOrAfter}, never later than the session's
 *       absolute lifetime (a SAML assertion cannot be re-read, so the actor signs in again after that).</li>
 * </ul>
 * The assertion XML is never stored or logged; a refusal logs one line with a reason code only.
 */
public final class SamlAuthenticationService {
    private static final Logger LOG = LoggerFactory.getLogger("com.securityexpert.nexus.ui2.saml");

    static final Duration REQUEST_LIFETIME = Duration.ofMinutes(5);
    /** An assertion issued longer ago than this (plus the skew) is refused even if its own conditions allow it. */
    static final Duration MAX_ASSERTION_AGE = Duration.ofMinutes(10);
    static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_PENDING_REQUESTS = 2000;
    private static final String PERSISTENT_FORMAT = "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent";
    private static final String TRANSIENT_FORMAT = "urn:oasis:names:tc:SAML:2.0:nameid-format:transient";
    private static final List<String> DISPLAY_NAME_CLAIMS = List.of(
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress",
            "http://schemas.xmlsoap.org/claims/CommonName");

    /** What the browser is sent to start the sign-in: a redirect URL, or an HTTP-POST form for the IdP. */
    public record StartedLogin(String redirectUrl, String postUrl, String samlRequest) { }

    public sealed interface Result {
        record Authenticated(AttemptOutcome.Success success) implements Result { }
        /** {@code code} is closed vocabulary; {@code message} is what the operator is shown. */
        record Refused(String code, String message) implements Result { }
        record Unavailable() implements Result { }
    }

    /** The validated facts of one assertion, nothing else: no XML. */
    static final class SamlLogin extends AbstractAuthenticationToken {
        final String assertionId;
        final String nameId;
        final String nameIdFormat;
        final Map<String, List<String>> attributes;
        final Instant issueInstant;
        final Instant notOnOrAfter;
        final Instant sessionNotOnOrAfter;

        SamlLogin(String assertionId, String nameId, String nameIdFormat, Map<String, List<String>> attributes,
                Instant issueInstant, Instant notOnOrAfter, Instant sessionNotOnOrAfter) {
            super(List.of());
            this.assertionId = assertionId;
            this.nameId = nameId;
            this.nameIdFormat = nameIdFormat;
            this.attributes = attributes;
            this.issueInstant = issueInstant;
            this.notOnOrAfter = notOnOrAfter;
            this.sessionNotOnOrAfter = sessionNotOnOrAfter;
            setAuthenticated(true);
        }

        @Override public Object getCredentials() { return ""; }
        @Override public Object getPrincipal() { return "saml"; }
        @Override public String toString() { return "SamlLogin[redacted]"; }
    }

    private record Live(SamlSettingsRecord record, RelyingPartyRegistration registration,
            OpenSaml5AuthenticationRequestResolver resolver, OpenSaml5AuthenticationProvider provider) { }

    private final SamlSettingsService settings;
    private final Clock clock;
    private final Duration maxProof;
    private final ConcurrentHashMap<String, Instant> pendingRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> seenAssertions = new ConcurrentHashMap<>();
    private SamlSettingsRecord loaded;
    private boolean loadedOnce;
    private Live live;
    private volatile long generation;

    /** @param maxProof the longest a SAML group set stays valid (the session's absolute lifetime) */
    public SamlAuthenticationService(SamlSettingsService settings, Clock clock, Duration maxProof) {
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
        this.maxProof = Objects.requireNonNull(maxProof);
    }

    /** Called after a settings save: drop the old registration and every pending sign-in now. */
    public synchronized void reload() {
        pendingRequests.clear();
        generation++;
        current();
    }

    private synchronized Optional<Live> current() {
        Optional<SamlSettingsRecord> row = settings.current();
        SamlSettingsRecord record = row.orElse(null);
        if (loadedOnce && Objects.equals(record, loaded)) return Optional.ofNullable(live);
        live = null;
        loaded = record;
        loadedOnce = true;
        if (record == null || !record.enabled()) return Optional.empty();
        try {
            RelyingPartyRegistration registration = registration(record, settings.spKey(record));
            OpenSaml5AuthenticationRequestResolver resolver = new OpenSaml5AuthenticationRequestResolver(
                    new InMemoryRelyingPartyRegistrationRepository(registration));
            OpenSaml5AuthenticationProvider provider = new OpenSaml5AuthenticationProvider();
            // OpenSAML reads a zero skew as "use the 5 minute default", so the tightest bound is one millisecond.
            Duration skew = record.clockSkewSeconds() == 0 ? Duration.ofMillis(1) : Duration.ofSeconds(record.clockSkewSeconds());
            provider.setAssertionValidator(OpenSaml5AuthenticationProvider.createDefaultAssertionValidatorWithParameters(
                    params -> params.put(SAML2AssertionValidationParameters.CLOCK_SKEW, skew)));
            provider.setResponseAuthenticationConverter(SamlAuthenticationService::convert);
            live = new Live(record, registration, resolver, provider);
        } catch (RuntimeException e) {
            LOG.warn("saml_settings_rejected");
            live = null;
        }
        return Optional.ofNullable(live);
    }

    static RelyingPartyRegistration registration(SamlSettingsRecord r, Optional<SamlSettingsService.SpKeyPair> spKey) {
        String base = SamlSettingsService.stripTrailingSlash(r.spBaseUrl());
        List<java.security.cert.X509Certificate> idpCerts = new ArrayList<>(SamlPem.certificates(r.idpSigningCertPem()));
        RelyingPartyRegistration.Builder builder = RelyingPartyRegistration.withRegistrationId(SamlSettingsService.REGISTRATION_ID)
                .entityId(r.spEntityId())
                .assertionConsumerServiceLocation(base + SamlSettingsService.ACS_PATH)
                .assertionConsumerServiceBinding(Saml2MessageBinding.POST)
                .assertingPartyMetadata(party -> {
                    party.entityId(r.idpEntityId());
                    party.singleSignOnServiceLocation(r.idpSsoUrl());
                    party.singleSignOnServiceBinding("POST".equals(r.idpSsoBinding()) ? Saml2MessageBinding.POST
                            : Saml2MessageBinding.REDIRECT);
                    party.wantAuthnRequestsSigned(r.signAuthnRequests());
                    party.verificationX509Credentials(c -> idpCerts.forEach(cert -> c.add(Saml2X509Credential.verification(cert))));
                });
        spKey.ifPresent(pair -> {
            if (r.signAuthnRequests()) {
                builder.signingX509Credentials(c -> c.add(Saml2X509Credential.signing(pair.privateKey(), pair.certificate())));
            }
            builder.decryptionX509Credentials(c -> c.add(Saml2X509Credential.decryption(pair.privateKey(), pair.certificate())));
        });
        return builder.build();
    }

    /** The SP metadata XML for the ADFS relying party trust; empty when SAML is off. */
    public Optional<String> metadata() {
        return current().map(live -> new OpenSaml5MetadataResolver().resolve(live.registration()));
    }

    /** Builds the AuthnRequest of a new sign-in and remembers its id. Empty when SAML is off or the queue is full. */
    public Optional<StartedLogin> start(HttpServletRequest request) {
        Optional<Live> current = current();
        if (current.isEmpty()) return Optional.empty();
        Instant now = clock.instant();
        pendingRequests.values().removeIf(expiry -> !now.isBefore(expiry));
        if (pendingRequests.size() >= MAX_PENDING_REQUESTS) {
            LOG.warn("saml_login_refused reason=too_many_pending");
            return Optional.empty();
        }
        AbstractSaml2AuthenticationRequest authn;
        try {
            authn = current.get().resolver().resolve(request);
        } catch (RuntimeException e) {
            LOG.warn("saml_login_refused reason=authn_request_not_built");
            return Optional.empty();
        }
        if (authn == null) return Optional.empty();
        pendingRequests.put(authn.getId(), now.plus(REQUEST_LIFETIME));
        if (authn instanceof Saml2RedirectAuthenticationRequest redirect) {
            UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(redirect.getAuthenticationRequestUri());
            addParameter("SAMLRequest", redirect.getSamlRequest(), uri);
            addParameter("RelayState", redirect.getRelayState(), uri);
            addParameter("SigAlg", redirect.getSigAlg(), uri);
            addParameter("Signature", redirect.getSignature(), uri);
            return Optional.of(new StartedLogin(uri.build(true).toUriString(), null, null));
        }
        Saml2PostAuthenticationRequest post = (Saml2PostAuthenticationRequest) authn;
        return Optional.of(new StartedLogin(null, post.getAuthenticationRequestUri(), post.getSamlRequest()));
    }

    private static void addParameter(String name, String value, UriComponentsBuilder builder) {
        if (value == null || value.isEmpty()) return;
        builder.queryParam(UriUtils.encode(name, StandardCharsets.ISO_8859_1), UriUtils.encode(value, StandardCharsets.ISO_8859_1));
    }

    /** Validates the base64 {@code SAMLResponse} of the assertion consumer service POST. */
    public Result consume(String samlResponseBase64) {
        Optional<Live> current = current();
        if (current.isEmpty()) return new Result.Unavailable();
        Live live = current.get();
        long startedGeneration = generation;
        if (samlResponseBase64 == null || samlResponseBase64.isBlank() || samlResponseBase64.length() > MAX_RESPONSE_BYTES * 2) {
            return refused("SAML_RESPONSE_MISSING", "The sign-in response from ADFS was missing or too large.");
        }
        byte[] xml;
        try {
            xml = Base64.getMimeDecoder().decode(samlResponseBase64);
        } catch (IllegalArgumentException e) {
            return refused("SAML_RESPONSE_MALFORMED", "The sign-in response from ADFS could not be read.");
        }
        if (xml.length > MAX_RESPONSE_BYTES) {
            return refused("SAML_RESPONSE_MISSING", "The sign-in response from ADFS was missing or too large.");
        }
        String inResponseTo = inResponseTo(xml);
        if (inResponseTo == null) {
            return refused("SAML_RESPONSE_MALFORMED", "The sign-in response from ADFS could not be read.");
        }
        Instant now = clock.instant();
        Instant requestExpiry = inResponseTo.isEmpty() ? null : pendingRequests.get(inResponseTo);
        if (requestExpiry == null || !now.isBefore(requestExpiry)) {
            return refused("SAML_UNSOLICITED_OR_REPLAYED", "This sign-in response does not answer a sign-in started here, "
                    + "or it was already used. Start the sign-in again.");
        }
        Saml2PostAuthenticationRequest authenticationRequest = Saml2PostAuthenticationRequest
                .withRelyingPartyRegistration(live.registration()).id(inResponseTo).samlRequest("-")
                .authenticationRequestUri(live.registration().getAssertingPartyMetadata().getSingleSignOnServiceLocation()).build();
        Saml2AuthenticationToken token = new Saml2AuthenticationToken(live.registration(),
                new String(xml, StandardCharsets.UTF_8), authenticationRequest);
        SamlLogin login;
        try {
            login = (SamlLogin) live.provider().authenticate(token);
        } catch (Saml2AuthenticationException e) {
            return refused(classify(e), message(classify(e)));
        } catch (RuntimeException e) {
            return refused("SAML_RESPONSE_REJECTED", message("SAML_RESPONSE_REJECTED"));
        }
        // From here the response is genuine; consume the request and the assertion id exactly once.
        if (pendingRequests.remove(inResponseTo) == null) {
            return refused("SAML_UNSOLICITED_OR_REPLAYED", message("SAML_UNSOLICITED_OR_REPLAYED"));
        }
        seenAssertions.values().removeIf(expiry -> !now.isBefore(expiry));
        Instant keepUntil = login.notOnOrAfter.plus(Duration.ofSeconds(live.record().clockSkewSeconds())).plus(Duration.ofMinutes(1));
        if (seenAssertions.putIfAbsent(sha256(login.assertionId), keepUntil) != null) {
            return refused("SAML_UNSOLICITED_OR_REPLAYED", message("SAML_UNSOLICITED_OR_REPLAYED"));
        }
        Duration skew = Duration.ofSeconds(live.record().clockSkewSeconds());
        if (login.issueInstant != null && login.issueInstant.plus(MAX_ASSERTION_AGE).plus(skew).isBefore(now)) {
            return refused("SAML_ASSERTION_EXPIRED", message("SAML_ASSERTION_EXPIRED"));
        }
        return build(live, login, now, startedGeneration);
    }

    private Result build(Live live, SamlLogin login, Instant now, long startedGeneration) {
        String normalized = normalizeNameId(login.nameId, login.nameIdFormat);
        if (normalized.isEmpty() || TRANSIENT_FORMAT.equals(login.nameIdFormat)) {
            return refused("SAML_NO_SUBJECT", message("SAML_NO_SUBJECT"));
        }
        SamlSettingsRecord record = live.record();
        Set<String> mapped = new LinkedHashSet<>();
        for (String value : login.attributes.getOrDefault(record.groupClaimAttribute(), List.of())) {
            if (value == null) continue;
            String v = value.strip();
            if (v.equalsIgnoreCase(record.adminGroupValue())) mapped.add(record.adminGroupValue());
            else if (v.equalsIgnoreCase(record.usersGroupValue())) mapped.add(record.usersGroupValue());
        }
        if (mapped.isEmpty()) {
            return refused("SAML_NO_AUTHORIZED_GROUP", message("SAML_NO_AUTHORIZED_GROUP"));
        }
        Instant validUntil = now.plus(maxProof);
        if (login.sessionNotOnOrAfter != null && login.sessionNotOnOrAfter.isBefore(validUntil)) {
            validUntil = login.sessionNotOnOrAfter;
        }
        if (!now.isBefore(validUntil)) return refused("SAML_ASSERTION_EXPIRED", message("SAML_ASSERTION_EXPIRED"));
        String displayName = null;
        for (String claim : DISPLAY_NAME_CLAIMS) {
            List<String> values = login.attributes.get(claim);
            if (values != null && !values.isEmpty()) { displayName = values.get(0); break; }
        }
        if (displayName == null) displayName = normalized;
        DirectoryObservation observation = new DirectoryObservation(SamlSettingsService.PROFILE_ID, normalized, mapped,
                displayName, now, validUntil, write -> {
                    // Published only while the settings this proof was validated against are still the live ones.
                    synchronized (this) {
                        if (generation != startedGeneration || !settings.isEnabled()) return false;
                        write.run();
                        return true;
                    }
                });
        LOG.info("saml_login_accepted groups={}", mapped.size());
        return new Result.Authenticated(new AttemptOutcome.Success(
                PrincipalFingerprint.of(SamlSettingsService.FINGERPRINT_PREFIX + normalized), observation));
    }

    /**
     * The NameID as an identity key (D2): NFC-normalized and trimmed; lower-cased unless the format is persistent (an opaque
     * identifier whose case is meaningful), so the same UPN or e-mail spelled with another case is one actor.
     */
    static String normalizeNameId(String nameId, String format) {
        if (nameId == null) return "";
        String normalized = Normalizer.normalize(nameId.strip(), Normalizer.Form.NFC);
        return PERSISTENT_FORMAT.equals(format) ? normalized : normalized.toLowerCase(Locale.ROOT);
    }

    private static SamlLogin convert(OpenSaml5AuthenticationProvider.ResponseToken token) {
        Response response = token.getResponse();
        List<Assertion> assertions = response.getAssertions();
        if (assertions.size() != 1) {
            throw new Saml2AuthenticationException(new org.springframework.security.saml2.core.Saml2Error(
                    "invalid_assertion", "The response must hold exactly one assertion."));
        }
        Assertion assertion = assertions.get(0);
        NameID nameId = assertion.getSubject() == null ? null : assertion.getSubject().getNameID();
        if (nameId == null || nameId.getValue() == null || nameId.getValue().isBlank()) {
            throw new Saml2AuthenticationException(new org.springframework.security.saml2.core.Saml2Error(
                    "subject_not_found", "The assertion has no NameID."));
        }
        if (assertion.getConditions() == null || assertion.getConditions().getNotOnOrAfter() == null) {
            throw new Saml2AuthenticationException(new org.springframework.security.saml2.core.Saml2Error(
                    "invalid_assertion", "The assertion has no expiry (Conditions NotOnOrAfter)."));
        }
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        for (AttributeStatement statement : assertion.getAttributeStatements()) {
            for (Attribute attribute : statement.getAttributes()) {
                List<String> values = new ArrayList<>();
                for (XMLObject value : attribute.getAttributeValues()) {
                    String text = value instanceof XSString s ? s.getValue() : value instanceof XSAny a ? a.getTextContent() : null;
                    if (text != null && !text.isBlank()) values.add(text);
                }
                if (attribute.getName() != null) attributes.computeIfAbsent(attribute.getName(), k -> new ArrayList<>()).addAll(values);
            }
        }
        Instant sessionNotOnOrAfter = null;
        for (AuthnStatement statement : assertion.getAuthnStatements()) {
            Instant at = statement.getSessionNotOnOrAfter();
            if (at != null && (sessionNotOnOrAfter == null || at.isBefore(sessionNotOnOrAfter))) sessionNotOnOrAfter = at;
        }
        return new SamlLogin(assertion.getID(), nameId.getValue(), nameId.getFormat(), attributes, assertion.getIssueInstant(),
                assertion.getConditions().getNotOnOrAfter(), sessionNotOnOrAfter);
    }

    /** The InResponseTo of the response root: "" when absent, null when the XML is not a parsable response. */
    private static String inResponseTo(byte[] xml) {
        try {
            XMLObject object = XMLObjectSupport.unmarshallFromInputStream(XMLObjectProviderRegistrySupport.getParserPool(),
                    new ByteArrayInputStream(xml));
            if (!(object instanceof Response response)) return null;
            return response.getInResponseTo() == null ? "" : response.getInResponseTo();
        } catch (Exception e) {
            return null;
        }
    }

    private static String classify(Saml2AuthenticationException e) {
        String code = e.getSaml2Error().getErrorCode();
        String description = e.getSaml2Error().getDescription() == null ? "" : e.getSaml2Error().getDescription().toLowerCase(Locale.ROOT);
        if ("invalid_signature".equals(code)) return "SAML_SIGNATURE_INVALID";
        if (description.contains("expired") || description.contains("notonorafter") || description.contains("no expiry")) {
            return "SAML_ASSERTION_EXPIRED";
        }
        if (description.contains("not yet valid") || description.contains("notbefore")) return "SAML_ASSERTION_NOT_YET_VALID";
        if (description.contains("audience")) return "SAML_WRONG_AUDIENCE";
        if (description.contains("recipient") || "invalid_destination".equals(code)) return "SAML_WRONG_RECIPIENT";
        if ("invalid_issuer".equals(code)) return "SAML_WRONG_ISSUER";
        if ("subject_not_found".equals(code)) return "SAML_NO_SUBJECT";
        if ("invalid_in_response_to".equals(code) || description.contains("inresponseto")) return "SAML_UNSOLICITED_OR_REPLAYED";
        if ("decryption_error".equals(code)) return "SAML_DECRYPTION_FAILED";
        return "SAML_RESPONSE_REJECTED";
    }

    static String message(String code) {
        return switch (code) {
            case "SAML_SIGNATURE_INVALID" -> "The sign-in response from ADFS is not signed by the configured ADFS certificate.";
            case "SAML_ASSERTION_EXPIRED" -> "The sign-in response from ADFS has expired. Check that the clocks of ADFS and Overwatch agree, then sign in again.";
            case "SAML_ASSERTION_NOT_YET_VALID" -> "The sign-in response from ADFS is not valid yet. Check that the clocks of ADFS and Overwatch agree.";
            case "SAML_WRONG_AUDIENCE" -> "The sign-in response from ADFS was issued for a different service provider (audience mismatch).";
            case "SAML_WRONG_RECIPIENT" -> "The sign-in response from ADFS was addressed to a different address than this service.";
            case "SAML_WRONG_ISSUER" -> "The sign-in response came from an identity provider that is not the configured ADFS.";
            case "SAML_NO_SUBJECT" -> "The sign-in response from ADFS does not identify a user.";
            case "SAML_UNSOLICITED_OR_REPLAYED" -> "This sign-in response does not answer a sign-in started here, or it was already used. Start the sign-in again.";
            case "SAML_DECRYPTION_FAILED" -> "The sign-in response from ADFS could not be decrypted with the service provider key.";
            case "SAML_NO_AUTHORIZED_GROUP" -> "You signed in to ADFS, but your account is in none of the groups Overwatch maps to a role, so you have no access. Ask a security administrator to add you to one.";
            default -> "The sign-in response from ADFS was not accepted.";
        };
    }

    private static Result refused(String code, String message) {
        LOG.info("saml_login_refused reason={}", code);
        return new Result.Refused(code, message);
    }

    private static String sha256(String value) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
