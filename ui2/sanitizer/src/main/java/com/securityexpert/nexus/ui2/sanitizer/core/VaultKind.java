package com.securityexpert.nexus.ui2.sanitizer.core;

/**
 * The kinds of token the mapping vault records (DATA_SANITIZER_CONTRACT.md §2 "Reversible on request"), each with
 * its canonical lookup form: an address in RFC 5952 / dotted form, a network as {@code address/len}, a MAC in
 * lower-case colon form, a name token exactly as emitted, a DNS name in lower case.
 */
public enum VaultKind {
    ADDRESS, NETWORK, MAC, LABEL, DEVICE, DNS_NAME;

    /** The stable lower-case name stored next to a vault row and used in lookup-key derivation. */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
