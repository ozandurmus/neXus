package com.securityexpert.nexus.ui2.worker.discovery.pan;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.discovery.pan.KeyDisposalOutcome;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumeration;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationResult;
import com.securityexpert.nexus.ui2.discovery.pan.RawDeviceInput;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanTrustRuleResolver;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.TrustResolution;

/**
 * T-1/T-2/T-4/T-5/T-7: the {@link PanoramaEnumeration} port implemented over
 * the existing {@link DeviceTransport} port, using only {@code
 * xmlApiCall} (contract §3). Exactly two calls per run -- the key-generation
 * call, then the one authorized enumeration -- and the in-memory key is
 * zeroed before this method returns, on every path (T-1: "closed when the
 * run ends" for a vendor key with no logout call). Every route this class
 * can send comes from {@link PanoramaApiRoutes} (T-2/T-3/T-5); every field
 * name it reads comes from {@link
 * com.securityexpert.nexus.ui2.discovery.pan.PanoramaApiFieldBinding}
 * through {@link PanoramaResponseParser} (FB-2); the only host it ever
 * dials is {@code request.panoramaHost()} (T-4) -- no address parsed out of
 * a response is ever a connection target.
 */
public final class PanoramaEnumerationAdapter implements PanoramaEnumeration {

    private static final System.Logger LOGGER = System.getLogger(PanoramaEnumerationAdapter.class.getName());
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final DeviceTransport transport;
    private final PanCredentialResolver credentialResolver;
    private final PanTrustRuleResolver trustRuleResolver;

    public PanoramaEnumerationAdapter(DeviceTransport transport, PanCredentialResolver credentialResolver,
            PanTrustRuleResolver trustRuleResolver) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.credentialResolver = Objects.requireNonNull(credentialResolver, "credentialResolver");
        this.trustRuleResolver = Objects.requireNonNull(trustRuleResolver, "trustRuleResolver");
    }

    /** T-5/SB-16/AC-3/AC-5: credential and trust resolution happen, and can refuse the run, before any request is ever sent. */
    @Override
    public PanoramaEnumerationResult run(PanoramaEnumerationRequest request) {
        PanCredentialMaterial credential = resolveCredentialOrNull(request.credentialRef());
        if (credential == null || isBlank(credential.username())) {
            return new PanoramaEnumerationResult.Refused(
                    "credential resolution did not produce usable material for the supplied credentialRef");
        }
        TrustResolution trust = resolveTrustOrNull(request.trustRuleRef());
        if (trust == null || trust instanceof TrustResolution.Unresolved) {
            return new PanoramaEnumerationResult.Refused(
                    "trust rule resolution did not produce a usable TLS trust configuration for the supplied trustRuleRef");
        }

        // T-4: the ONLY ApiTarget this run ever builds -- request.panoramaHost(), nothing parsed later.
        ApiTarget target = new ApiTarget(request.panoramaHost(), baseUrl(request));
        int requestCount = 0;
        char[] key = null;
        try {
            XmlApiSpec keygenSpec = PanoramaApiRoutes.keyGeneration(credential.username(), credential.password());
            XmlApiResult keygenResult = transport.xmlApiCall(target, keygenSpec, REQUEST_TIMEOUT);
            requestCount++;
            if (keygenResult instanceof XmlApiResult.Failed failed) {
                LOGGER.log(System.Logger.Level.WARNING, "Panorama keygen transport failed: " + failed.reason());
                return new PanoramaEnumerationResult.Failed(
                        "panorama key generation transport failed: " + failed.reason(), requestCount, KeyDisposalOutcome.NOT_OBTAINED);
            }
            if (!(keygenResult instanceof XmlApiResult.Completed keygenCompleted)) {
                LOGGER.log(System.Logger.Level.WARNING, "Panorama keygen unexpected transport result: " + keygenResult);
                return new PanoramaEnumerationResult.Failed(
                        "panorama key generation did not complete: unexpected transport result", requestCount, KeyDisposalOutcome.NOT_OBTAINED);
            }
            if (keygenCompleted.httpStatus() != 200) {
                LOGGER.log(System.Logger.Level.WARNING, "Panorama keygen HTTP status: " + keygenCompleted.httpStatus() + ", body: " + keygenCompleted.body());
                return new PanoramaEnumerationResult.Failed(
                        "panorama key generation HTTP " + keygenCompleted.httpStatus(), requestCount, KeyDisposalOutcome.NOT_OBTAINED);
            }
            Optional<String> extractedKey = PanoramaResponseParser.extractKey(keygenCompleted.body());
            if (extractedKey.isEmpty()) {
                LOGGER.log(System.Logger.Level.WARNING, "Panorama keygen carried no key in 200 OK body: " + keygenCompleted.body());
                return new PanoramaEnumerationResult.Failed(
                        "panorama key generation response carried no key", requestCount, KeyDisposalOutcome.NOT_OBTAINED);
            }
            key = extractedKey.get().toCharArray();

            XmlApiSpec enumerationSpec = PanoramaApiRoutes.managedDeviceEnumeration(key);
            XmlApiResult enumerationResult = transport.xmlApiCall(target, enumerationSpec, REQUEST_TIMEOUT);
            requestCount++;
            if (enumerationResult instanceof XmlApiResult.Failed failed) {
                LOGGER.log(System.Logger.Level.WARNING, "Panorama enumeration transport failed: " + failed.reason());
                return new PanoramaEnumerationResult.Failed(
                        "panorama managed-device enumeration transport failed: " + failed.reason(), requestCount, KeyDisposalOutcome.DISCARDED);
            }
            if (!(enumerationResult instanceof XmlApiResult.Completed enumerationCompleted) || enumerationCompleted.httpStatus() != 200) {
                int status = (enumerationResult instanceof XmlApiResult.Completed comp) ? comp.httpStatus() : 0;
                String body = (enumerationResult instanceof XmlApiResult.Completed comp) ? comp.body() : "";
                LOGGER.log(System.Logger.Level.WARNING, "Panorama enumeration returned HTTP status: " + status + ", body: " + body);
                return new PanoramaEnumerationResult.Failed(
                        "panorama managed-device enumeration HTTP " + status, requestCount, KeyDisposalOutcome.DISCARDED);
            }
            List<RawDeviceInput> devices = PanoramaResponseParser.extractDevices(enumerationCompleted.body());
            return new PanoramaEnumerationResult.Completed(devices, requestCount, KeyDisposalOutcome.DISCARDED);
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Panorama enumeration failed with exception: " + e.getMessage(), e);
            KeyDisposalOutcome outcome = key == null ? KeyDisposalOutcome.NOT_OBTAINED : KeyDisposalOutcome.DISCARDED;
            return new PanoramaEnumerationResult.Failed("panorama enumeration did not complete: " + e.getMessage(), requestCount, outcome);
        } finally {
            // T-1: the key is discarded from memory whether the run succeeded or failed.
            if (key != null) {
                Arrays.fill(key, '\0');
            }
        }
    }

    private PanCredentialMaterial resolveCredentialOrNull(String credentialRef) {
        try {
            return credentialResolver.resolve(credentialRef);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private TrustResolution resolveTrustOrNull(String trustRuleRef) {
        try {
            return trustRuleResolver.resolveTrust(trustRuleRef);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String baseUrl(PanoramaEnumerationRequest request) {
        return "https://" + request.panoramaHost() + ":" + request.panoramaPort();
    }
}
