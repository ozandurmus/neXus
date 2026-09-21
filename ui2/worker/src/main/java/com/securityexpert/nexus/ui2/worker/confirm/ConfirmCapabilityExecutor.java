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
        boolean preferInteractiveShell = isKnownSparkModel(request.modelHint());
        try {
            String identityOutput = execOutput(session,
                    DeviceFirstContactCommandSet.forStep(Vendor.CHECK_POINT, ContactStepKind.IDENTITY_READ),
                    preferInteractiveShell);
            if (identityOutput == null) {
                long elapsed = System.currentTimeMillis() - overallStart;
                LOG.log(System.Logger.Level.WARNING,
                        "[CONFIRM_FAILED] Identity read command timed out or failed for {0}:{1} after {2}ms",
                        target.host(), target.port(), elapsed);
                return new ConfirmResult.ConnectFailed("command_failed: identity read timed out or failed (after " + elapsed + "ms)");
            }
            String haPeerOutput = execOutput(session,
                    DeviceFirstContactCommandSet.forStep(Vendor.CHECK_POINT, ContactStepKind.HA_PEER_READ),
                    preferInteractiveShell);
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

    /** Discovery-known Check Point Quantum Spark/Gaia Embedded model tokens (ported verbatim from
     * the pre-Java product's own evidence-driven classification) -- used only to choose which
     * already-approved channel to try first below, never to alter the closed command set itself. */
    private static final java.util.Set<String> SPARK_MODEL_TOKENS = java.util.Set.of(
            "1500", "1530", "1550", "1570", "1590", "1600", "1800", "1900", "2000");

    /** True when a discovery-sourced model hint (management-plane observation, never confirmed
     * evidence -- see the Evidence laws) already names a Quantum Spark/Gaia Embedded appliance,
     * e.g. from a Check Point Management Server's own "hardware" field, joined in before this
     * confirm ever ran ({@code JooqDeviceRepository.DEVICE_SUMMARY_SELECT}'s {@code dc.model}). */
    private static boolean isKnownSparkModel(Optional<String> modelHint) {
        return modelHint.map(model -> SPARK_MODEL_TOKENS.stream().anyMatch(model::contains)).orElse(false);
    }

    /** DeviceFirstContactCommandSet's own literal forms, tried in order (gate entry 1's documented
     * "may be corrected at this one site" clause): a Quantum Spark/Gaia Embedded device's landing
     * shell and CLI surface cannot be assumed ahead of time (AGENTS.md Check Point), so the next
     * form is tried on failure, timeout, or blank output -- previously dead code, only
     * literalForms().get(0) was ever sent, so such a device's identity read always ran out its
     * full timeout for nothing (Product Owner measured live, 2026-09-21). This mirrors the
     * pre-Java product's own real-fleet-proven Check Point direct-SSH probe, including
     * its PTY allocation: Gaia/Gaia Embedded restricted shells were measured there to answer more
     * consistently, and not at all in some cases, without one.
     *
     * <p>When {@code preferInteractiveShell} is set (a discovery-known Spark/Gaia Embedded model,
     * see {@link #isKnownSparkModel}), the two channels below are tried in the opposite order: the
     * exec channel is doomed on these appliances (measured live, 2026-09-21: ~120s burned on four
     * exec-channel timeouts before the interactive shell -- which always answered -- ever ran), so
     * the interactive shell is tried first and the exec channel becomes the fallback, in case the
     * hint is wrong. Same closed set of literals either way, only the trial order changes.</p> */
    private String execOutput(TransportSession session, DeviceFirstContactCommandSet entry, boolean preferInteractiveShell) {
        java.util.function.BiFunction<TransportSession, String, String> first =
                preferInteractiveShell ? this::execInteractiveCommand : this::execOneCommand;
        java.util.function.BiFunction<TransportSession, String, String> second =
                preferInteractiveShell ? this::execOneCommand : this::execInteractiveCommand;
        for (String cmd : entry.literalForms()) {
            String output = first.apply(session, cmd);
            if (output != null && !output.isBlank()) {
                return output;
            }
        }
        String lastFromSecond = null;
        for (String cmd : entry.literalForms()) {
            lastFromSecond = second.apply(session, cmd);
            if (lastFromSecond != null && !lastFromSecond.isBlank()) {
                return lastFromSecond;
            }
        }
        return lastFromSecond;
    }

    private String execInteractiveCommand(TransportSession session, String cmd) {
        long startMs = System.currentTimeMillis();
        ExecResult result;
        try {
            result = transport.execInteractive(session, new ExecSpec(cmd), READ_TIMEOUT);
        } catch (com.securityexpert.nexus.ui2.jobs.transport.TransportNotImplementedException notImplemented) {
            return null;
        }
        long elapsedMs = System.currentTimeMillis() - startMs;
        return switch (result) {
            case ExecResult.Completed completed -> {
                LOG.log(System.Logger.Level.INFO,
                        "[CONFIRM_EXEC_INTERACTIVE] cmd=\"{0}\" completed in {1}ms (length={2})",
                        cmd, elapsedMs, completed.output().length());
                yield completed.output();
            }
            case ExecResult.TimedOut timedOut -> {
                LOG.log(System.Logger.Level.WARNING,
                        "[CONFIRM_EXEC_INTERACTIVE_TIMEOUT] cmd=\"{0}\" TIMED OUT after {1}ms!",
                        cmd, elapsedMs);
                yield null;
            }
            case ExecResult.ChannelFailed failed -> {
                LOG.log(System.Logger.Level.WARNING,
                        "[CONFIRM_EXEC_INTERACTIVE_FAILED] cmd=\"{0}\" failed after {1}ms: {2}",
                        cmd, elapsedMs, failed.reason());
                yield null;
            }
        };
    }

    private String execOneCommand(TransportSession session, String cmd) {
        long startMs = System.currentTimeMillis();
        ExecResult result = transport.exec(session, new ExecSpec(cmd, true), READ_TIMEOUT);
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
