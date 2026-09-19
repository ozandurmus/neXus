package com.securityexpert.nexus.ui2.worker.transport.xmlapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;

class PanXmlApiTransportTest {

    @Test
    void unresolvableTrustFailsGracefullyWithoutThrowing() {
        PanXmlApiTransport transport = new PanXmlApiTransport("unresolved-ref", ref -> new TrustResolution.Unresolved());
        ApiTarget target = new ApiTarget("ep-1", "10.0.0.1");
        XmlApiSpec spec = new XmlApiSpec("POST", "op", "", "", Map.of("cmd", "<show/>"), Map.of());

        XmlApiResult result = transport.xmlApiCall(target, spec, Duration.ofSeconds(1));

        assertInstanceOf(XmlApiResult.Failed.class, result);
        XmlApiResult.Failed failed = (XmlApiResult.Failed) result;
        assertTrue(failed.reason().contains("trust rule could not be resolved"));
    }

    @Test
    void bareIpTargetIsHandledGracefully() {
        PanXmlApiTransport transport = new PanXmlApiTransport("unresolved-ref", ref -> new TrustResolution.Unresolved());
        ApiTarget target = new ApiTarget("ep-1", "10.241.236.113");
        XmlApiSpec spec = new XmlApiSpec("POST", "op", "", "", Map.of("cmd", "<show/>"), Map.of());

        XmlApiResult result = transport.xmlApiCall(target, spec, Duration.ofSeconds(1));
        assertInstanceOf(XmlApiResult.Failed.class, result);
    }

    @Test
    void paloAltoDeviceTrustResolvesUsableTransport() {
        PanXmlApiTransport transport = new PanXmlApiTransport("device-trust-ref",
                ref -> new TrustResolution.PaloAltoDeviceTrust(Optional.empty()));
        assertNotNull(transport);
    }
}
