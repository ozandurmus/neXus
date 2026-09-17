package com.securityexpert.nexus.ui2.worker.confirm;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.worker.confirm.DeviceFirstContactCommandSet.ContactStepKind;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;

/**
 * The confirm's device contact (contract EC-11, EC-12): {@code connect} ->
 * identity read -> HA/peer read -> {@code disconnect}, over exactly the
 * closed command set ({@link DeviceFirstContactCommandSet}) and nothing
 * else -- every literal string sent to {@link #transport} is taken from
 * that enum, never built ad hoc. Credential resolution failure (CS-3,
 * SB-16, EC-3) refuses before any contact: for Check Point this happens
 * inside {@link DeviceTransport#connect}'s own store-backed resolver
 * (propagated here as an {@link IllegalStateException}, never a partially
 * completed attempt); for Palo Alto -- whose {@code xmlApiCall} has no
 * injected resolver of its own (T-5) -- this class resolves the
 * credential itself before the first call.
 *
 * <p>Palo Alto's key generation is the {@code xml_api_call} capability's
 * own {@code connect} step (C4 §2.3: "opens... the API key/session...
 * through the trust preflight"), not one of the four gate entries -- its
 * gate applicability is {@code NOT_APPLICABLE} by the same rule that
 * exempts an SSH authentication handshake, so it is deliberately absent
 * from {@link DeviceFirstContactCommandSet}.</p>
 */
public final class ConfirmCapabilityExecutor {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern API_KEY = Pattern.compile("<key>([^<]+)</key>");

    private final DeviceTransport transport;
    private final PanCredentialResolver panCredentialResolver;
    private final ConfirmReadParser checkPointParser;
    private final ConfirmReadParser paloAltoParser;

    public ConfirmCapabilityExecutor(DeviceTransport transport, PanCredentialResolver panCredentialResolver) {
        this(transport, panCredentialResolver, new CheckPointConfirmReadParser(), new PaloAltoConfirmReadParser());
    }

    ConfirmCapabilityExecutor(DeviceTransport transport, PanCredentialResolver panCredentialResolver,
            ConfirmReadParser checkPointParser, ConfirmReadParser paloAltoParser) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.panCredentialResolver = panCredentialResolver;
        this.checkPointParser = Objects.requireNonNull(checkPointParser, "checkPointParser");
        this.paloAltoParser = Objects.requireNonNull(paloAltoParser, "paloAltoParser");
    }

    public ConfirmResult confirm(ConfirmRequest request) {
        return switch (request.vendor()) {
            case CHECK_POINT -> confirmCheckPoint(request);
            case PALO_ALTO -> confirmPaloAlto(request);
        };
    }

    private ConfirmResult confirmCheckPoint(ConfirmRequest request) {
        var target = request.connectionTarget()
                .orElseThrow(() -> new IllegalArgumentException("check_point confirm requires a connectionTarget"));
        ConnectSpec spec = new ConnectSpec(request.credentialRef(), request.trustRuleRef(), Optional.empty());
        ConnectResult connectResult;
        try {
            connectResult = transport.connect(target, spec, READ_TIMEOUT);
        } catch (IllegalStateException credentialUnresolvable) {
            return new ConfirmResult.CredentialUnresolvable(String.valueOf(credentialUnresolvable.getMessage()));
        }
        if (!(connectResult instanceof ConnectResult.Authenticated authenticated)) {
            return new ConfirmResult.ConnectFailed(describeConnect(connectResult));
        }
        TransportSession session = authenticated.session();
        try {
            String identityOutput = execOutput(session,
                    DeviceFirstContactCommandSet.forStep(Vendor.CHECK_POINT, ContactStepKind.IDENTITY_READ));
            String haPeerOutput = execOutput(session,
                    DeviceFirstContactCommandSet.forStep(Vendor.CHECK_POINT, ContactStepKind.HA_PEER_READ));

            PresentedIdentity presented =
                    checkPointParser.presentedIdentity(identityOutput, session.presentedIdentity());
            ObservedFacts facts = withHaRole(checkPointParser.observedFacts(identityOutput),
                    checkPointParser.haRole(haPeerOutput));
            HaPeerClaim haPeerClaim = checkPointParser.haPeerClaim(haPeerOutput);
            Optional<String> selfReference = checkPointParser.selfReferenceForPeer(identityOutput);
            return new ConfirmResult.Completed(presented, facts, haPeerClaim, selfReference);
        } finally {
            transport.disconnect(session);
        }
    }

    private ConfirmResult confirmPaloAlto(ConfirmRequest request) {
        var target = request.apiTarget()
                .orElseThrow(() -> new IllegalArgumentException("palo_alto confirm requires an apiTarget"));
        PanCredentialMaterial credential;
        try {
            credential = panCredentialResolver.resolve(request.credentialRef());
        } catch (IllegalStateException credentialUnresolvable) {
            return new ConfirmResult.CredentialUnresolvable(String.valueOf(credentialUnresolvable.getMessage()));
        }

        XmlApiResult keyResult = transport.xmlApiCall(target,
                new XmlApiSpec("POST", "keygen", "", "",
                        Map.of("user", credential.username(), "password", new String(credential.password())),
                        Map.of()),
                READ_TIMEOUT);
        Optional<String> apiKey = xmlOutput(keyResult).flatMap(ConfirmCapabilityExecutor::extractApiKey);
        if (apiKey.isEmpty()) {
            return new ConfirmResult.ConnectFailed("palo alto key generation did not return a usable key");
        }
        Map<String, String> headers = Map.of("X-PAN-KEY", apiKey.get());

        String identityOutput = xmlApiOutput(target,
                DeviceFirstContactCommandSet.forStep(Vendor.PALO_ALTO, ContactStepKind.IDENTITY_READ), headers);
        String haPeerOutput = xmlApiOutput(target,
                DeviceFirstContactCommandSet.forStep(Vendor.PALO_ALTO, ContactStepKind.HA_PEER_READ), headers);

        PresentedIdentity presented = paloAltoParser.presentedIdentity(identityOutput, Optional.empty());
        ObservedFacts facts =
                withHaRole(paloAltoParser.observedFacts(identityOutput), paloAltoParser.haRole(haPeerOutput));
        HaPeerClaim haPeerClaim = paloAltoParser.haPeerClaim(haPeerOutput);
        Optional<String> selfReference = paloAltoParser.selfReferenceForPeer(identityOutput);
        return new ConfirmResult.Completed(presented, facts, haPeerClaim, selfReference);
    }

    private String execOutput(TransportSession session, DeviceFirstContactCommandSet entry) {
        // The primary literal form is what this movement sends; the fallback form entry 1 also
        // declares is a documented alternative for a later correction, never sent speculatively in
        // the same attempt (AGENTS.md: no invented retry beyond what the gate entry's own retry column
        // states -- "none; a failure ends the confirm with its outcome").
        ExecResult result = transport.exec(session, new ExecSpec(entry.literalForms().get(0)), READ_TIMEOUT);
        return switch (result) {
            case ExecResult.Completed completed -> completed.output();
            case ExecResult.TimedOut ignored -> null;
            case ExecResult.ChannelFailed ignored -> null;
        };
    }

    private String xmlApiOutput(com.securityexpert.nexus.ui2.jobs.transport.ApiTarget target,
            DeviceFirstContactCommandSet entry, Map<String, String> headers) {
        XmlApiResult result = transport.xmlApiCall(target,
                new XmlApiSpec("GET", "op", "", "direct_firewall", Map.of("cmd", entry.literalForms().get(0)), headers),
                READ_TIMEOUT);
        return xmlOutput(result).orElse(null);
    }

    private static Optional<String> xmlOutput(XmlApiResult result) {
        return result instanceof XmlApiResult.Completed completed ? Optional.of(completed.body()) : Optional.empty();
    }

    private static Optional<String> extractApiKey(String body) {
        Matcher matcher = API_KEY.matcher(body);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static ObservedFacts withHaRole(ObservedFacts facts, Optional<String> haRole) {
        return new ObservedFacts(facts.hostname(), facts.model(), facts.softwareVersion(), haRole);
    }

    private static String describeConnect(ConnectResult result) {
        return switch (result) {
            case ConnectResult.AuthenticationFailed failed -> "authentication_failed: " + failed.reason();
            case ConnectResult.HostKeyRejected rejected -> rejected.reason().startsWith("host_key_mismatch:")
                    ? rejected.reason()
                    : "host_key_rejected: " + rejected.reason();
            case ConnectResult.TimedOut ignored -> "timed_out";
            case ConnectResult.Authenticated ignored -> "authenticated";
        };
    }
}
