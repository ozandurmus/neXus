package com.securityexpert.nexus.ui2.identity.ldap;

/** A classified directory failure. Carries only the reason code: never a value read from or sent to the server. */
final class DirectoryFailure extends Exception {
    private final DirectoryReason reason;

    DirectoryFailure(DirectoryReason reason) {
        super(reason.code(), null, false, false);
        this.reason = reason;
    }

    DirectoryReason reason() {
        return reason;
    }
}
