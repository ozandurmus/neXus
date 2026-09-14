package com.securityexpert.nexus.ui2.worker.configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.stream.XMLStreamException;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiStreamOutcome;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationArtefactRecord;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationReadKind;
import com.securityexpert.nexus.ui2.worker.confirm.IdentityMismatchEvaluator;
import com.securityexpert.nexus.ui2.worker.confirm.PresentedIdentity;
import com.securityexpert.nexus.ui2.worker.configuration.cp.CheckPointGaiaConfigProcessor;
import com.securityexpert.nexus.ui2.worker.configuration.pan.PaloAltoConfigStreamProcessor;
import com.securityexpert.nexus.ui2.worker.configuration.pan.PanoramaCrossCheckPort;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;

/**
 * The configuration-collect device contact (14G CG-1/CG-4, 13F CF-1/CF-2):
 * {@code connect}/identity-refresh/{@code show configuration}/{@code
 * disconnect} for Check Point, or the key dance plus the three XML reads
 * for Palo Alto -- mirrors {@code worker.inventory.InventoryCapabilityExecutor}'s
 * own connect/exec/disconnect shape.
 *
 * <p>Identity mismatch (13F ID-M1..M4) reuses {@link
 * IdentityMismatchEvaluator} exactly as inventory collection does:
 * {@code WARN_AND_CONTINUE}/{@code MATCH}/{@code NO_BASELINE} all continue
 * to the read plan; {@code REFUSE} (the strict posture, off by default)
 * stops before any read runs.</p>
 *
 * <p>CG-1's three identity-refresh reads run and are gated, but -- like
 * {@code InventoryCapabilityExecutor}'s own {@code cphaprob stat} -- are
 * not deeply parsed here: the identity this movement compares against the
 * recorded baseline is the SSH session's own presented host key (Check
 * Point) or the {@code show system info} serial (Palo Alto), exactly as
 * inventory collection already does.</p>
 */
public final class ConfigurationCapabilityExecutor {

    private static final java.time.Duration IDENTITY_TIMEOUT = java.time.Duration.ofSeconds(30);
    private static final java.time.Duration CP_CONFIG_TIMEOUT = java.time.Duration.ofSeconds(60);
    private static final java.time.Duration PAN_EFFECTIVE_RUNNING_TIMEOUT = java.time.Duration.ofSeconds(120);
    private static final java.time.Duration PAN_OTHER_TIMEOUT = java.time.Duration.ofSeconds(60);
    private static final Pattern API_KEY = Pattern.compile("<key>([^<]+)</key>");
    private static final Pattern SERIAL_TAG = Pattern.compile("(?is)<serial>\\s*([^<]+?)\\s*</serial>");

    private final DeviceTransport transport;
    private final PanCredentialResolver panCredentialResolver;
    private final ArtefactStore artefactStore;
    private final PanoramaCrossCheckPort panoramaCrossCheck;

    public ConfigurationCapabilityExecutor(DeviceTransport transport, PanCredentialResolver panCredentialResolver,
            ArtefactStore artefactStore, PanoramaCrossCheckPort panoramaCrossCheck) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.panCredentialResolver = panCredentialResolver;
        this.artefactStore = Objects.requireNonNull(artefactStore, "artefactStore");
        this.panoramaCrossCheck = panoramaCrossCheck == null ? PanoramaCrossCheckPort.NONE : panoramaCrossCheck;
    }

    public ConfigurationResult collect(ConfigurationRequest request, String deviceId, String jobId,
            Optional<PresentedIdentity> recordedIdentity, boolean strictRefuseEnabled) {
        return switch (request.vendor()) {
            case CHECK_POINT -> collectCheckPoint(request, deviceId, jobId, recordedIdentity, strictRefuseEnabled);
            case PALO_ALTO -> collectPaloAlto(request, deviceId, jobId, recordedIdentity, strictRefuseEnabled);
        };
    }

    private ConfigurationResult collectCheckPoint(ConfigurationRequest request, String deviceId, String jobId,
            Optional<PresentedIdentity> recordedIdentity, boolean strictRefuseEnabled) {
        ConnectionTarget target = request.connectionTarget()
                .orElseThrow(() -> new IllegalArgumentException("check_point configuration collect requires a connectionTarget"));
        ConnectSpec spec = new ConnectSpec(request.credentialRef(), request.trustRuleRef(), Optional.empty());
        ConnectResult connectResult;
        try {
            connectResult = transport.connect(target, spec, IDENTITY_TIMEOUT);
        } catch (IllegalStateException credentialUnresolvable) {
            return new ConfigurationResult.CredentialUnresolvable(String.valueOf(credentialUnresolvable.getMessage()));
        }
        if (!(connectResult instanceof ConnectResult.Authenticated authenticated)) {
            return new ConfigurationResult.ConnectFailed(describeConnect(connectResult));
        }
        TransportSession session = authenticated.session();
        try {
            PresentedIdentity presented = new PresentedIdentity(session.presentedIdentity().orElse(""), Optional.empty());
            IdentityMismatchEvaluator.Decision decision =
                    IdentityMismatchEvaluator.evaluate(recordedIdentity, presented, strictRefuseEnabled);
            if (decision == IdentityMismatchEvaluator.Decision.REFUSE) {
                return new ConfigurationResult.IdentityMismatchRefused(
                        "presented identity does not match the recorded baseline; strict posture refused the contact");
            }

            for (String read : ConfigurationReadPlan.CHECK_POINT_IDENTITY_READS) {
                execOutput(session, read, IDENTITY_TIMEOUT);
            }
            String rawConfig = execOutput(session, ConfigurationReadPlan.CP_SHOW_CONFIGURATION, CP_CONFIG_TIMEOUT);

            CheckPointGaiaConfigProcessor.Processed processed = CheckPointGaiaConfigProcessor.process(rawConfig);
            ArtefactStore.ArtefactHandle handle = null;
            try {
                handle = artefactStore.open(deviceId, jobId, "check_point", false);
                handle.sink().write(rawConfig.getBytes(StandardCharsets.UTF_8));
                ArtefactStore.ArtefactMetadata metadata = handle.finish();
                ConfigurationRunData runData = new ConfigurationRunData(ConfigurationReadKind.SHOW_CONFIGURATION, true,
                        processed.canonicalHash(), processed.withheldLineCount(), Optional.of(processed.sanitizedText()),
                        processed.index(), List.of(), toRecord(metadata, deviceId, jobId, "check_point"));
                return new ConfigurationResult.Completed(List.of(runData));
            } catch (IOException e) {
                closeQuietly(handle);
                return new ConfigurationResult.ArtefactStoreFailed("artefact store write failed: " + e.getMessage());
            }
        } finally {
            transport.disconnect(session);
        }
    }

    private ConfigurationResult collectPaloAlto(ConfigurationRequest request, String deviceId, String jobId,
            Optional<PresentedIdentity> recordedIdentity, boolean strictRefuseEnabled) {
        ApiTarget target = request.apiTarget()
                .orElseThrow(() -> new IllegalArgumentException("palo_alto configuration collect requires an apiTarget"));
        PanCredentialMaterial credential;
        try {
            credential = panCredentialResolver.resolve(request.credentialRef());
        } catch (IllegalStateException credentialUnresolvable) {
            return new ConfigurationResult.CredentialUnresolvable(String.valueOf(credentialUnresolvable.getMessage()));
        }

        XmlApiResult keyResult = transport.xmlApiCall(target,
                new XmlApiSpec("POST", "keygen", "", "",
                        Map.of("user", credential.username(), "password", new String(credential.password())),
                        Map.of()),
                IDENTITY_TIMEOUT);
        Optional<String> apiKey = xmlOutput(keyResult).flatMap(ConfigurationCapabilityExecutor::extractApiKey);
        if (apiKey.isEmpty()) {
            return new ConfigurationResult.ConnectFailed("palo alto key generation did not return a usable key");
        }
        Map<String, String> headers = Map.of("X-PAN-KEY", apiKey.get());

        String identityOutput = xmlApiOutput(target, ConfigurationReadPlan.PAN_SHOW_SYSTEM_INFO, headers, IDENTITY_TIMEOUT);
        String serial = firstMatch(SERIAL_TAG, identityOutput).orElse("");
        PresentedIdentity presented = new PresentedIdentity(serial, Optional.empty());
        IdentityMismatchEvaluator.Decision decision =
                IdentityMismatchEvaluator.evaluate(recordedIdentity, presented, strictRefuseEnabled);
        if (decision == IdentityMismatchEvaluator.Decision.REFUSE) {
            return new ConfigurationResult.IdentityMismatchRefused(
                    "presented serial does not match the recorded baseline; strict posture refused the contact");
        }

        try {
            List<ConfigurationRunData> runs = new ArrayList<>();

            String activeOutput = xmlApiOutputForm(target, ConfigurationReadPlan.PAN_ACTIVE_FORM_PARAMS, headers,
                    PAN_OTHER_TIMEOUT);
            runs.add(buildSimpleRun(deviceId, jobId, ConfigurationReadKind.ACTIVE, false, activeOutput));

            ConfigurationRunData effectiveRunning = collectEffectiveRunningStreamed(target, headers, deviceId, jobId, serial);
            runs.add(effectiveRunning);

            String mergedOutput = xmlApiOutput(target, ConfigurationReadPlan.PAN_MERGED, headers, PAN_OTHER_TIMEOUT);
            runs.add(buildSimpleRun(deviceId, jobId, ConfigurationReadKind.MERGED, false, mergedOutput));

            return new ConfigurationResult.Completed(runs);
        } catch (IOException e) {
            return new ConfigurationResult.ArtefactStoreFailed("artefact store write failed: " + e.getMessage());
        }
    }

    private ConfigurationRunData collectEffectiveRunningStreamed(ApiTarget target, Map<String, String> headers,
            String deviceId, String jobId, String deviceSerial) throws IOException {
        ArtefactStore.ArtefactHandle handle = artefactStore.open(deviceId, jobId, "palo_alto", true);
        try {
            XmlApiSpec spec = new XmlApiSpec("POST", "op", "", "direct_firewall",
                    Map.of("cmd", ConfigurationReadPlan.PAN_EFFECTIVE_RUNNING), headers);
            XmlApiStreamOutcome<PaloAltoConfigStreamProcessor.Processed> outcome = transport.xmlApiCallStreaming(target,
                    spec, PAN_EFFECTIVE_RUNNING_TIMEOUT, inputStream -> {
                        InputStream teed = new TeeInputStream(inputStream, handle.sink());
                        try {
                            PaloAltoConfigStreamProcessor.Processed processed = PaloAltoConfigStreamProcessor.process(teed);
                            teed.readAllBytes(); // drain any trailing bytes past the document end into the artefact sink
                            return processed;
                        } catch (XMLStreamException e) {
                            throw new IOException("effective-running could not be parsed as XML", e);
                        }
                    });
            if (!(outcome instanceof XmlApiStreamOutcome.Completed<PaloAltoConfigStreamProcessor.Processed> completed)) {
                closeQuietly(handle);
                throw new IOException("effective-running streaming call failed: "
                        + (outcome instanceof XmlApiStreamOutcome.Failed<?> failed ? failed.reason() : "unknown"));
            }
            ArtefactStore.ArtefactMetadata metadata = handle.finish();
            List<com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationOverride> overrides =
                    panoramaCrossCheck.nameOverrideSources(deviceSerial, completed.handled().overrides());
            return new ConfigurationRunData(ConfigurationReadKind.EFFECTIVE_RUNNING, true, metadata.plaintextSha256(),
                    0, Optional.empty(), completed.handled().index(), overrides,
                    toRecord(metadata, deviceId, jobId, "palo_alto"));
        } catch (IOException e) {
            closeQuietly(handle);
            throw e;
        }
    }

    private ConfigurationRunData buildSimpleRun(String deviceId, String jobId, String readKind, boolean primary,
            String rawText) throws IOException {
        ArtefactStore.ArtefactHandle handle = artefactStore.open(deviceId, jobId, "palo_alto", false);
        try {
            handle.sink().write(rawText.getBytes(StandardCharsets.UTF_8));
            ArtefactStore.ArtefactMetadata metadata = handle.finish();
            return new ConfigurationRunData(readKind, primary, metadata.plaintextSha256(), 0, Optional.empty(),
                    List.of(), List.of(), toRecord(metadata, deviceId, jobId, "palo_alto"));
        } catch (IOException e) {
            closeQuietly(handle);
            throw e;
        }
    }

    private static ConfigurationArtefactRecord toRecord(ArtefactStore.ArtefactMetadata metadata, String deviceId,
            String jobId, String vendor) {
        return new ConfigurationArtefactRecord(metadata.ref().value(), deviceId, jobId, vendor,
                metadata.plaintextSha256(), metadata.plaintextBytes(), metadata.ciphertextSha256(),
                metadata.ciphertextBytes(), metadata.compression(), metadata.keyId(), metadata.wrappedDataKey());
    }

    private static void closeQuietly(ArtefactStore.ArtefactHandle handle) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (IOException ignored) {
            // best-effort cleanup of a temp artefact file after a write failure
        }
    }

    private String execOutput(TransportSession session, String command, java.time.Duration timeout) {
        ExecResult result = transport.exec(session, new ExecSpec(command), timeout);
        return switch (result) {
            case ExecResult.Completed completed -> completed.output();
            case ExecResult.TimedOut ignored -> "";
            case ExecResult.ChannelFailed ignored -> "";
        };
    }

    private String xmlApiOutput(ApiTarget target, String cmd, Map<String, String> headers, java.time.Duration timeout) {
        XmlApiResult result = transport.xmlApiCall(target,
                new XmlApiSpec("GET", "op", "", "direct_firewall", Map.of("cmd", cmd), headers), timeout);
        return xmlOutput(result).orElse("");
    }

    private String xmlApiOutputForm(ApiTarget target, Map<String, String> formParams, Map<String, String> headers,
            java.time.Duration timeout) {
        XmlApiResult result = transport.xmlApiCall(target,
                new XmlApiSpec("GET", "config", "", "direct_firewall", formParams, headers), timeout);
        return xmlOutput(result).orElse("");
    }

    private static Optional<String> xmlOutput(XmlApiResult result) {
        return result instanceof XmlApiResult.Completed completed ? Optional.of(completed.body()) : Optional.empty();
    }

    private static Optional<String> extractApiKey(String body) {
        Matcher matcher = API_KEY.matcher(body);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Optional<String> firstMatch(Pattern pattern, String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static String describeConnect(ConnectResult result) {
        return switch (result) {
            case ConnectResult.AuthenticationFailed failed -> "authentication_failed: " + failed.reason();
            case ConnectResult.HostKeyRejected rejected -> "host_key_rejected: " + rejected.reason();
            case ConnectResult.TimedOut ignored -> "timed_out";
            case ConnectResult.Authenticated ignored -> "authenticated";
        };
    }
}
