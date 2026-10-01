package com.securityexpert.nexus.ui2.worker.credential;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.credential.CredentialRecord;
import com.securityexpert.nexus.ui2.persistence.credential.CredentialRepository;
import com.securityexpert.nexus.ui2.persistence.credential.CredentialStoreComposition.ResolverComponents;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRecord;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.SnmpSettings;

class StoreBackedSnmpCredentialResolverTest {
    @Test
    void resolvesByOpaqueReferenceAndClearsMaterialWithoutNetworkAccess() {
        var references = mock(CredentialReferenceRepository.class);
        var credentials = mock(CredentialRepository.class);
        var cipher = CredentialStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
        var now = Instant.now();
        var resolver = new StoreBackedSnmpCredentialResolver(new ResolverComponents(references, credentials, cipher));
        when(references.find("fixture-reference")).thenReturn(Optional.of(
                new CredentialReferenceRecord("fixture-reference", "snmp_v3", "fixture-id", now)));
        for (CredentialKind kind : CredentialKind.values()) {
            var settings = kind == CredentialKind.SNMP_V3 ? new SnmpSettings("authPriv", "SHA-256", "AES-128") : null;
            when(credentials.findById("fixture-id")).thenReturn(Optional.of(new CredentialRecord(
                    "fixture-id", "Synthetic SNMP", kind, "synthetic-user", cipher.encrypt("synthetic-primary"),
                    settings == null ? null : cipher.encrypt("synthetic-privacy"), "fixture-key", false, false,
                    "fixture-actor", now, now, settings)));
            if (kind == CredentialKind.SNMP_V3 || kind == CredentialKind.SNMP_V1_V2C) {
                var material = resolver.resolve("fixture-reference");
                assertEquals("synthetic-primary", new String(material.communityOrAuthSecret()));
                if (settings != null) assertEquals("synthetic-privacy", new String(material.privacySecret()));
                assertEquals("SnmpCredentialMaterial[REDACTED]", material.toString());
                material.close();
                assertTrue(new String(material.communityOrAuthSecret()).chars().allMatch(c -> c == 0));
                assertTrue(new String(material.privacySecret()).chars().allMatch(c -> c == 0));
            } else {
                assertThrows(IllegalStateException.class, () -> resolver.resolve("fixture-reference"));
            }
        }
        assertThrows(IllegalStateException.class, () -> resolver.resolve("missing-reference"));
    }
}
