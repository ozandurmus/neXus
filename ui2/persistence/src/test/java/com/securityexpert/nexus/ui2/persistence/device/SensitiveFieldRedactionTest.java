package com.securityexpert.nexus.ui2.persistence.device;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * B1-4b contract §7: {@code endpoints.address_ref} (the management
 * address) is CLASS 2 and "never appears... in a log line". {@link
 * EndpointRecord} and {@link DeviceDraft} both carry it; this test proves
 * their {@code toString()} -- the representation a stray log statement or
 * exception message would use -- never includes the raw value.
 */
class SensitiveFieldRedactionTest {

    private static final String SYNTHETIC_ADDRESS_REF = "synthetic-mgmt-host.invalid:22";

    @Test
    void endpointRecordToStringNeverContainsAddressRef() {
        EndpointRecord record = new EndpointRecord("endpoint-1", "device-1", "ssh_exec", SYNTHETIC_ADDRESS_REF,
                Instant.now());

        assertFalse(record.toString().contains(SYNTHETIC_ADDRESS_REF),
                "EndpointRecord.toString() must never contain address_ref (contract §7)");
    }

    @Test
    void deviceDraftToStringNeverContainsAddressRef() {
        DeviceDraft draft = new DeviceDraft("device-1", "vendor-hint-synthetic", "manual_registration", false,
                "cred-ref-1", "endpoint-1", "ssh_exec", SYNTHETIC_ADDRESS_REF);

        assertFalse(draft.toString().contains(SYNTHETIC_ADDRESS_REF),
                "DeviceDraft.toString() must never contain address_ref (contract §7)");
    }
}
