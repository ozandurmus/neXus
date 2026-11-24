package com.securityexpert.nexus.ui2.worker.snmp;

import java.util.Map;

import com.securityexpert.nexus.ui2.worker.credential.StoreBackedSnmpCredentialResolver.Material;

/**
 * One SNMPv3 authPriv poll of one device: a GET of fixed scalar OIDs, then a bounded GETBULK walk of fixed table columns.
 * Read-only by construction: implementations build GET and GETBULK PDUs only, and refuse any OID outside the vendor's
 * gated reads. The answer stays in memory and is parsed by {@link SnmpMetricParser}; it is never stored or logged.
 */
public interface SnmpClient {

    /** Per request timeout and retries (design section 5; V269 gate rows). */
    int TIMEOUT_MS = 3000;
    int RETRIES = 1;
    /** GETBULK bound per poll (V269 session_reuse_rule): at most 8 requests of 16 repetitions. */
    int MAX_BULK_REQUESTS = 8;
    int MAX_REPETITIONS = 16;
    int PORT = 161;

    /**
     * Polls {@code host} for {@code profile}'s GET instances and walk columns. The caller has checked the profile's gate
     * rows ({@link SnmpVendorProfile#gated}); the client refuses any OID the profile does not permit.
     */
    SnmpAnswer poll(String host, Material credential, SnmpVendorProfile profile);

    /** A value as the agent sent it. MISSING = noSuchObject, noSuchInstance, endOfMibView or absent. */
    record SnmpValue(Kind kind, Long number, String text) {
        public enum Kind { NUMBER, TEXT, OID, MISSING }

        public static final SnmpValue MISSING = new SnmpValue(Kind.MISSING, null, null);

        public static SnmpValue number(long value) { return new SnmpValue(Kind.NUMBER, value, null); }
        public static SnmpValue text(String value) { return new SnmpValue(Kind.TEXT, null, value); }
        public static SnmpValue oid(String value) { return new SnmpValue(Kind.OID, null, value); }

        @Override
        public String toString() {
            return "SnmpValue[" + kind + "]";
        }
    }

    /** How a poll ended. Only {@link Answered} carries values. */
    sealed interface SnmpAnswer {
        /** The agent answered at authPriv. {@code values} holds every GET instance and every walked row by full OID. */
        record Answered(Map<String, SnmpValue> values) implements SnmpAnswer {
            public Answered {
                values = Map.copyOf(values);
            }

            public SnmpValue get(String oid) {
                return values.getOrDefault(oid, SnmpValue.MISSING);
            }

            @Override
            public String toString() {
                return "Answered[" + values.size() + " values]";
            }
        }

        /** No answer within timeout and retry, or no usable answer. */
        record NoAnswer() implements SnmpAnswer {}

        /** The agent refused the user, the authentication or the privacy (USM report or USM error status). */
        record AuthRefused() implements SnmpAnswer {}
    }
}
