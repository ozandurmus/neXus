package com.securityexpert.nexus.ui2.worker.discovery.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.discovery.pan.KeyDisposalOutcome;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationResult;
import com.securityexpert.nexus.ui2.discovery.pan.RawDeviceInput;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanTrustRuleResolver;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.TrustResolution;

/**
 * AC-1 (T-4, the decisive test, and §11 check 16), AC-2, AC-3, AC-5, AC-6's
 * DI-3 to DI-6 no-filtering and T-7's no-raw-retention property, exercised
 * through a full fixture run with {@link FakePanDeviceTransport} -- never a
 * real socket, never a real Panorama.
 *
 * <p>Written before {@code PanoramaEnumerationAdapter} existed: the first
 * commit on this lane had only the platform-core port types, so this class
 * (and {@link FakePanDeviceTransport}) could not yet compile -- a stronger
 * "observed failing" than a green/red JUnit run, recorded in SESSION_CLOSE.</p>
 */
class PanoramaEnumerationAdapterTest {

    private static final String HOST = "fixture-panorama-host";
    private static final int PORT = 4443;
    private static final String CREDENTIAL_REF = "fixture-credential-ref";
    private static final String TRUST_RULE_REF = "fixture-trust-rule-ref";

    private static final PanCredentialResolver ALWAYS_RESOLVES_CREDENTIAL =
            ref -> new PanCredentialMaterial(PanFixtures.FIXTURE_USERNAME, PanFixtures.FIXTURE_PASSWORD.toCharArray());
    private static final PanTrustRuleResolver ALWAYS_RESOLVES_TRUST =
            ref -> new TrustResolution.CaBundlePath("/fixture/ca-bundle.pem");

    private PanoramaEnumerationRequest request() {
        return new PanoramaEnumerationRequest(HOST, PORT, CREDENTIAL_REF, TRUST_RULE_REF);
    }

    /** T-4 -- THE DECISIVE TEST. Across a full run, exactly one distinct ApiTarget base URL is ever dialed. */
    @Test
    void acrossAFullRunExactlyOneDistinctBaseUrlIsEverDialed() {
        FakePanDeviceTransport transport = new FakePanDeviceTransport(happyPathHandler());
        PanoramaEnumerationAdapter adapter = new PanoramaEnumerationAdapter(transport, ALWAYS_RESOLVES_CREDENTIAL, ALWAYS_RESOLVES_TRUST);

        PanoramaEnumerationResult result = adapter.run(request());

        assertInstanceOf(PanoramaEnumerationResult.Completed.class, result);
        Set<String> distinctBaseUrls = new HashSet<>();
        for (ApiTarget target : transport.targets()) {
            distinctBaseUrls.add(target.baseUrl());
        }
        assertEquals(1, distinctBaseUrls.size(), "every ApiTarget base URL dialed: " + distinctBaseUrls);
        assertTrue(distinctBaseUrls.iterator().next().contains(HOST));
    }

    /** §11 check 16: exactly two Panorama requests per run, regardless of estate size. */
    @Test
    void exactlyTwoRequestsAreIssuedPerRun() {
        FakePanDeviceTransport transport = new FakePanDeviceTransport(happyPathHandler());
        PanoramaEnumerationAdapter adapter = new PanoramaEnumerationAdapter(transport, ALWAYS_RESOLVES_CREDENTIAL, ALWAYS_RESOLVES_TRUST);

        PanoramaEnumerationResult.Completed result =
                assertInstanceOf(PanoramaEnumerationResult.Completed.class, adapter.run(request()));

        assertEquals(2, transport.requestCount());
        assertEquals(2, result.requestCount());
        assertEquals(KeyDisposalOutcome.DISCARDED, result.keyDisposalOutcome());
    }

    /** T-2/T-3/T-5: every request issued is a member of the closed route set, and none carries a target parameter. */
    @Test
    void everyRequestIssuedIsAMemberOfTheClosedRouteSet() {
        FakePanDeviceTransport transport = new FakePanDeviceTransport(happyPathHandler());
        PanoramaEnumerationAdapter adapter = new PanoramaEnumerationAdapter(transport, ALWAYS_RESOLVES_CREDENTIAL, ALWAYS_RESOLVES_TRUST);

        adapter.run(request());

        assertFalse(transport.specsIssued().isEmpty());
        for (XmlApiSpec spec : transport.specsIssued()) {
            assertTrue(PanoramaApiRoutes.isMemberOfClosedSet(spec), "not a closed-set member: " + spec);
            assertFalse(spec.formParams().containsKey("target"));
        }
    }

    /** AC-5: a credentialRef the resolver cannot resolve produces a typed refusal with zero requests. */
    @Test
    void unresolvableCredentialRefusesBeforeAnyRequest() {
        PanCredentialResolver throwing = ref -> {
            throw new IllegalStateException("fixture-secret-backend-unreachable: user=fixture-admin host=" + HOST);
        };
        FakePanDeviceTransport transport = new FakePanDeviceTransport(happyPathHandler());
        PanoramaEnumerationAdapter adapter = new PanoramaEnumerationAdapter(transport, throwing, ALWAYS_RESOLVES_TRUST);

        PanoramaEnumerationResult result = adapter.run(request());

        assertEquals(0, transport.requestCount());
        PanoramaEnumerationResult.Refused refused = assertInstanceOf(PanoramaEnumerationResult.Refused.class, result);
        assertFalse(refused.reason().contains("fixture-admin"));
        assertFalse(refused.reason().contains("fixture-secret-backend-unreachable"));
    }

    /** AC-3: an unresolvable trust rule produces a typed refusal with zero requests. */
    @Test
    void unresolvableTrustRuleRefusesBeforeAnyRequest() {
        PanTrustRuleResolver unresolved = ref -> new TrustResolution.Unresolved();
        FakePanDeviceTransport transport = new FakePanDeviceTransport(happyPathHandler());
        PanoramaEnumerationAdapter adapter = new PanoramaEnumerationAdapter(transport, ALWAYS_RESOLVES_CREDENTIAL, unresolved);

        PanoramaEnumerationResult result = adapter.run(request());

        assertEquals(0, transport.requestCount());
        assertInstanceOf(PanoramaEnumerationResult.Refused.class, result);
    }

    /**
     * AC-2/T-7: the credential, the key and the raw response body text are never reachable from
     * the result -- unlike cp's {@code RawCandidateInput}, {@link
     * com.securityexpert.nexus.ui2.discovery.pan.RawDeviceInput} carrying parsed candidate data
     * (serial, hostname, address) is the discovery payload itself and is expected in the result;
     * it is {@link PanDiscoveryRunReport} that must avoid rendering those (see {@code
     * PanDiscoveryRunReportTest}).
     */
    @Test
    void noCredentialKeyOrRawResponseTextIsReachableFromTheResult() {
        FakePanDeviceTransport transport = new FakePanDeviceTransport(happyPathHandler());
        PanoramaEnumerationAdapter adapter = new PanoramaEnumerationAdapter(transport, ALWAYS_RESOLVES_CREDENTIAL, ALWAYS_RESOLVES_TRUST);

        PanoramaEnumerationResult result = adapter.run(request());
        String resultAsText = String.valueOf(result);

        assertFalse(resultAsText.contains(PanFixtures.FIXTURE_PASSWORD));
        assertFalse(resultAsText.contains(PanFixtures.FIXTURE_KEY));
        assertFalse(resultAsText.contains("<entry>"), "no raw response markup may be reachable from the result");
        assertFalse(resultAsText.contains("<devices>"), "no raw response markup may be reachable from the result");
    }

    /** DI-3 to DI-6: a serial-less entry and a non-established, non-connected entry both reach the assembler untouched. */
    @Test
    void serialLessAndNonConnectedEntriesBothReachTheAssembler() {
        FakePanDeviceTransport transport = new FakePanDeviceTransport(happyPathHandler());
        PanoramaEnumerationAdapter adapter = new PanoramaEnumerationAdapter(transport, ALWAYS_RESOLVES_CREDENTIAL, ALWAYS_RESOLVES_TRUST);

        PanoramaEnumerationResult.Completed result =
                assertInstanceOf(PanoramaEnumerationResult.Completed.class, adapter.run(request()));

        java.util.List<RawDeviceInput> devices = result.candidates();
        assertEquals(3, devices.size());
        assertTrue(devices.stream().anyMatch(d -> !d.stableIdentifier().isPresent()), "serial-less entry must still be returned");
        assertTrue(devices.stream().anyMatch(d -> d.connectionState().isPresent() && d.connectionState().get().equals("disconnected")),
                "non-connected entry must still be returned");
    }

    /** T-1: a keygen call that never produces a key is a typed Failed with NOT_OBTAINED, never a thrown exception. */
    @Test
    void keyGenerationFailureIsReportedAsFailedWithNotObtained() {
        Function<XmlApiSpec, XmlApiResult> handler = spec -> new XmlApiResult.Completed(500, "fixture-server-error");
        FakePanDeviceTransport transport = new FakePanDeviceTransport(handler);
        PanoramaEnumerationAdapter adapter = new PanoramaEnumerationAdapter(transport, ALWAYS_RESOLVES_CREDENTIAL, ALWAYS_RESOLVES_TRUST);

        PanoramaEnumerationResult result = adapter.run(request());

        PanoramaEnumerationResult.Failed failed = assertInstanceOf(PanoramaEnumerationResult.Failed.class, result);
        assertEquals(1, failed.requestCount());
        assertEquals(KeyDisposalOutcome.NOT_OBTAINED, failed.keyDisposalOutcome());
    }

    /** AC-4: a keygen response carrying a DOCTYPE with an external entity fails closed without resolving it. */
    @Test
    void xxeBearingResponseFailsClosedWithoutResolvingTheExternalEntity() {
        Function<XmlApiSpec, XmlApiResult> handler = spec -> new XmlApiResult.Completed(200, PanFixtures.xxeKeyGenerationResponse());
        FakePanDeviceTransport transport = new FakePanDeviceTransport(handler);
        PanoramaEnumerationAdapter adapter = new PanoramaEnumerationAdapter(transport, ALWAYS_RESOLVES_CREDENTIAL, ALWAYS_RESOLVES_TRUST);

        PanoramaEnumerationResult result = adapter.run(request());

        PanoramaEnumerationResult.Failed failed = assertInstanceOf(PanoramaEnumerationResult.Failed.class, result);
        assertFalse(String.valueOf(failed).contains("/etc/passwd"), "the external entity must never be resolved");
        assertEquals(KeyDisposalOutcome.NOT_OBTAINED, failed.keyDisposalOutcome());
    }

    static Function<XmlApiSpec, XmlApiResult> happyPathHandler() {
        return spec -> {
            if ("keygen".equals(spec.type())) {
                return new XmlApiResult.Completed(200, PanFixtures.keyGenerationResponse(PanFixtures.FIXTURE_KEY));
            }
            if ("op".equals(spec.type())) {
                String entryA = PanFixtures.deviceEntry("fixture-serial-1", "fixture-host-1", "", "198.51.100.10", null,
                        "fixture-serial-2", "connected", "fixture-ts-1", "fixture-cert-status-1", "fixture-cert-expiry-1");
                String entryB = PanFixtures.deviceEntry("fixture-serial-2", "fixture-host-2", "", "198.51.100.11", null,
                        "fixture-serial-1", "connected", null, null, null);
                String entrySerialLess = PanFixtures.deviceEntry(null, "fixture-host-3", "", null, null,
                        null, "disconnected", null, null, null);
                return new XmlApiResult.Completed(200, PanFixtures.enumerationResponse(entryA, entryB, entrySerialLess));
            }
            throw new AssertionError("unscripted fixture spec type: " + spec.type());
        };
    }
}
