package com.securityexpert.nexus.ui2.worker.transport.xmlapi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;

class PanTranscriptCaptureTest {
    @Test void excludesGeneratedAndSentApiKeys() {
        String secret = "synthetic-secret";
        XmlApiSpec keygen = new XmlApiSpec("POST", "keygen", "", "",
                Map.of("password", secret), Map.of());
        assertFalse(PanXmlApiTransport.safeBody(keygen, "<response><key>" + secret + "</key></response>")
                .contains(secret));
        assertFalse(PanXmlApiTransport.safeBody(keygen, "echo " + secret).contains(secret));
        XmlApiSpec call = new XmlApiSpec("POST", "config", "show", "",
                Map.of(), Map.of("X-PAN-KEY", secret));
        assertFalse(PanXmlApiTransport.safeBody(call, "echo " + secret).contains(secret));
    }
}
