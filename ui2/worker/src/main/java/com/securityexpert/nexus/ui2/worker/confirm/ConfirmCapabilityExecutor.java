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

    private static final System.Logger LOG = System.getLogger(ConfirmCapabilityExecutor.class.getName());
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
        long overallStart = System.currentTimeMillis();
        LOG.log(System.Logger.Level.INFO, "[CONFIRM_START] Check Point target={0}:{1}", target.host(), target.port());
        ConnectSpec spec = new ConnectSpec(request.credentialRef(), request.trustRuleRef(), Optional.empty());
        ConnectResult connectResult;
        try {
            connectResult = transport.connect(target, spec, READ_TIMEOUT);
        } catch (IllegalStateException credentialUnresolvable) {
            long elapsed = System.currentTimeMillis() - overallStart;
            LOG.log(System.Logger.Level.WARNING,
                    "[CONFIRM_FAILED] Credential unresolvable for {0}:{1} after {2}ms: {3}",
                    target.host(), target.port(), elapsed, credentialUnresolvable.getMessage());
            return new ConfirmResult.CredentialUnresolvable(String.valueOf(credentialUnresolvable.getMessage()));
        }
        if (!(connectResult instanceof ConnectResult.Authenticated authenticated)) {
            long elapsed = System.currentTimeMillis() - overallStart;
            LOG.log(System.Logger.Level.WARNING,
                    "[CONFIRM_FAILED] Connect failed for {0}:{1} after {2}ms: {3}",
                    target.host(), target.port(), elapsed, describeConnect(connectResult));
            return new ConfirmResult.ConnectFailed(describeConnect(connectResult));
        }
        TransportSession session = authenticated.session();
        try {
            String identityOutput = execOutput(session,
                    DeviceFirstContactCommandSet.forStep(Vendor.CHECK_POINT, ContactStepKind.IDENTITY_READ));
            if (identityOutput == null) {
                long elapsed = System.currentTimeMillis() - overallStart;
                LOG.log(System.Logger.Level.WARNING,
                        "[CONFIRM_FAILED] Identity read command timed out or failed for {0}:{1} after {2}ms",
                        target.host(), target.port(), elapsed);
                return new ConfirmResult.ConnectFailed("command_failed: identity read timed out or failed (after " + elapsed + "ms)");
            }
            String haPeerOutput = execOutput(session,
                    DeviceFirstContactCommandSet.forStep(Vendor.CHECK_POINT, ContactStepKind.HA_PEER_READ));
            if (haPeerOutput == null) {
                long elapsed = System.currentTimeMillis() - overallStart;
                LOG.log(System.Logger.Level.WARNING,
                        "[CONFIRM_FAILED] HA peer read command timed out or failed for {0}:{1} after {2}ms",
                        target.host(), target.port(), elapsed);
                return new ConfirmResult.ConnectFailed("command_failed: ha peer read timed out or failed (after " + elapsed + "ms)");
            }

            PresentedIdentity presented =
                    checkPointParser.presentedIdentity(identityOutput, session.presentedIdentity());
            ObservedFacts facts = withHaRole(checkPointParser.observedFacts(identityOutput),
                    checkPointParser.haRole(haPeerOutput));
            HaPeerClaim haPeerClaim = checkPointParser.haPeerClaim(haPeerOutput);
            Optional<String> selfReference = checkPointParser.selfReferenceForPeer(identityOutput);
            long elapsed = System.currentTimeMillis() - overallStart;
            LOG.log(System.Logger.Level.INFO,
                    "[CONFIRM_COMPLETE] Check Point target={0}:{1} completed in {2}ms: model={3}, version={4}",
                    target.host(), target.port(), elapsed, facts.model().orElse("unknown"), facts.softwareVersion().orElse("unknown"));
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
            String failureReason = keyResult instanceof XmlApiResult.Failed failed
                    ? "palo alto key generation failed: " + failed.reason()
                    : "palo alto key generation did not return a usable key";
            return new ConfirmResult.ConnectFailed(failureReason);
        }
        Map<String, String> headers = Map.of("X-PAN-KEY", apiKey.get());

        String identityOutput = xmlApiOutput(target,
                DeviceFirstContactCommandSet.forStep(Vendor.PALO_ALTO, ContactStepKind.IDENTITY_READ), headers);
        if (identityOutput == null || identityOutput.isBlank() || isXmlError(identityOutput)) {
            return new ConfirmResult.ConnectFailed("palo alto identity read timed out or failed");
        }

        PresentedIdentity presented = paloAltoParser.presentedIdentity(identityOutput, Optional.empty());
        if (presented.primary() == null || presented.primary().isBlank()) {
            return new ConfirmResult.ConnectFailed("palo alto identity read returned empty serial number");
        }

        String haPeerOutput = xmlApiOutput(target,
                DeviceFirstContactCommandSet.forStep(Vendor.PALO_ALTO, ContactStepKind.HA_PEER_READ), headers);

        ObservedFacts facts =
                withHaRole(paloAltoParser.observedFacts(identityOutput), paloAltoParser.haRole(haPeerOutput));
        HaPeerClaim haPeerClaim = paloAltoParser.haPeerClaim(haPeerOutput);
        Optional<String> selfReference = paloAltoParser.selfReferenceForPeer(identityOutput);
        return new ConfirmResult.Completed(presented, facts, haPeerClaim, selfReference);
    }

    private static boolean isXmlError(String xml) {
        return xml.contains("status=\"error\"") || xml.contains("status='error'");
    }

    /** DeviceFirstContactCommandSet's own primary/fallback pair (gate entry 1's documented intent,
     * PO-approved 2026-09-14): a Quantum Spark/Gaia Embedded device landing directly in Clish never
     * answers the bare Expert-mode form, so the second literal ({@code clish -c "..."}) is tried
     * when the first one fails or times out -- previously dead code, only literalForms().get(0)
     * was ever sent, so such a device's identity read always ran out its full timeout for nothing
     * (Product Owner measured live, 2026-09-21). Every literal tried is still one of the two the
     * gate document names; no new command is introduced. */
    private String execOutput(TransportSession session, DeviceFirstContactCommandSet entry) {
        String output = null;
        for (String cmd : entry.literalForms()) {
            output = execOneCommand(session, cmd);
            if (output != null) {
                break;
            }
        }
        return output;
    }

    private String execOneCommand(TransportSession session, String cmd) {
        long startMs = System.currentTimeMillis();
        ExecResult result = transport.exec(session, new ExecSpec(cmd), READ_TIMEOUT);
        long elapsedMs = System.currentTimeMillis() - startMs;
        return switch (result) {
            case ExecResult.Completed completed -> {
                LOG.log(System.Logger.Level.INFO,
                        "[CONFIRM_EXEC] cmd=\"{0}\" completed in {1}ms (exit={2}, length={3})",
                        cmd, elapsedMs, completed.exitStatus(), completed.output().length());
                yield completed.output();
            }
            case ExecResult.TimedOut timedOut -> {
                LOG.log(System.Logger.Level.WARNING,
                        "[CONFIRM_EXEC_TIMEOUT] cmd=\"{0}\" TIMED OUT after {1}ms!",
                        cmd, elapsedMs);
                yield null;
            }
            case ExecResult.ChannelFailed failed -> {
                LOG.log(System.Logger.Level.WARNING,
                        "[CONFIRM_EXEC_FAILED] cmd=\"{0}\" failed after {1}ms: {2}",
                        cmd, elapsedMs, failed.reason());
                yield null;
            }
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
            case ConnectResult.TimedOut timedOut -> timedOut.reason();
            case ConnectResult.Authenticated ignored -> "authenticated";
        };
    }
}
