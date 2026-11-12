package com.securityexpert.nexus.ui2.identity.ldap;

/**
 * Closed reason-code vocabulary for a refused directory sign-in (design §4). The only thing a log line may say
 * about a failed attempt: never the user name, a DN, the password or a server address.
 */
public enum DirectoryReason {
    SERVICE_BIND_FAILED("service_bind_failed"),
    USER_NOT_FOUND("user_not_found"),
    USER_AMBIGUOUS("user_ambiguous"),
    USER_BIND_FAILED("user_bind_failed"),
    NOT_IN_GROUP("not_in_group"),
    /** The certificate chain or its dates were not accepted (certificate-below mode). */
    TLS_TRUST_FAILED("tls_trust_failed"),
    /** Certificate-below mode: the chain is trusted, but the certificate does not name the configured address. */
    TLS_NAME_MISMATCH("tls_name_mismatch"),
    /**
     * TCP connected, then TLS negotiation failed before any certificate decision: the server reset or closed the
     * connection, or refused the handshake (no shared protocol version or cipher suite, or no LDAPS certificate).
     */
    TLS_HANDSHAKE_FAILED("tls_handshake_failed"),
    TIMEOUT("timeout"),
    CONNECT_FAILED("connect_failed"),
    STRONG_AUTH_REQUIRED("strong_auth_required"),
    SEARCH_FAILED("search_failed"),
    DIRECTORY_DISABLED("directory_disabled"),
    SETTINGS_INVALID("settings_invalid"),
    DIRECTORY_ERROR("directory_error");

    private final String code;

    DirectoryReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Refusals that say "this person may not sign in"; every other reason is "the directory is unavailable". */
    public boolean refusesTheUser() {
        return this == USER_NOT_FOUND || this == USER_AMBIGUOUS || this == USER_BIND_FAILED || this == NOT_IN_GROUP;
    }

    public static DirectoryReason fromCode(String code) {
        for (DirectoryReason reason : values()) if (reason.code.equals(code)) return reason;
        return DIRECTORY_ERROR;
    }
}
