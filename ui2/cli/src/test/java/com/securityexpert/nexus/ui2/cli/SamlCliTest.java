package com.securityexpert.nexus.ui2.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** CLI parity for Administration > SAML: the metadata XML and the SP key come from files and are merged in at send time. */
class SamlCliTest {
    @Test
    void aFileValueReplacesAnyValueTheSettingsFileCarried() {
        String merged = NexusSessionCli.withStringField(
                "{ \"spBaseUrl\": \"https://nexus.example.test\", \"spPrivateKeyPem\": \"from-settings\", \"clockSkewSeconds\": 60 }",
                "spPrivateKeyPem", "line1\nline2 \"q\"");
        assertTrue(merged.startsWith("{\"spPrivateKeyPem\":\"line1\\nline2 \\\"q\\\"\","), merged);
        assertFalse(merged.contains("from-settings"), merged);
        assertTrue(merged.contains("\"clockSkewSeconds\": 60"), merged);
    }

    @Test
    void anEmptyObjectGetsJustTheField() {
        assertEquals("{\"idpMetadataXml\":\"<x/>\"}", NexusSessionCli.withStringField("{}", "idpMetadataXml", "<x/>"));
        assertThrows(IllegalArgumentException.class, () -> NexusSessionCli.withStringField("[1]", "f", "x"));
    }

    @Test
    void aTrailingFieldOfTheFileIsReplacedWithoutLeavingACommaBehind() {
        String merged = NexusSessionCli.withStringField("{\"a\":1,\"idpMetadataXml\":\"old\"}", "idpMetadataXml", "new");
        assertEquals("{\"idpMetadataXml\":\"new\",\"a\":1}", merged);
    }
}
