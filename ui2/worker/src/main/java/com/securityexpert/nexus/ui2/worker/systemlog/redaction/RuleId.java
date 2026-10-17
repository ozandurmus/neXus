package com.securityexpert.nexus.ui2.worker.systemlog.redaction;

import java.util.List;

/** The rules of the rule set, with the stable ids used as keys of {@link RedactionResult#countsByRule()}. */
public enum RuleId {
    /** {@code scheme://user:SECRET@host}. */
    URL_CREDENTIALS("url_credentials"),
    /** Gaia {@code set|add user NAME password|password-hash VALUE}, {@code set expert-password VALUE}. */
    GAIA_USER_PASSWORD("gaia_user_password"),
    /** {@code password-hash VALUE}, {@code expert-password-hash VALUE} (a hash-shaped value). */
    GAIA_PASSWORD_HASH("gaia_password_hash"),
    /** SNMP community strings: Gaia {@code snmp ... community X}, snmpd.conf {@code rocommunity X} and similar. */
    SNMP_COMMUNITY("snmp_community"),
    /** SNMP v3 USM auth/priv pass phrases and keys. */
    SNMP_USM("snmp_usm"),
    /** RADIUS / TACACS+ shared secret or key. */
    AAA_SECRET("aaa_secret"),
    /** {@code --password VALUE} style command-line flags. */
    CLI_FLAG("cli_flag"),
    /** {@code password=VALUE}, {@code passwd=}, {@code key=}, {@code secret=}, {@code "token": "VALUE"} and similar. */
    SECRET_ASSIGNMENT("secret_assignment"),
    /** A base64 / JWT blob after {@code Authorization:}, {@code Basic}, {@code Bearer}, {@code auth}, {@code token}. */
    AUTH_BLOB("auth_blob"),
    /** A long hex string after {@code key}, {@code secret}, {@code psk}, {@code password}, {@code token}. */
    HEX_KEY("hex_key"),
    /** A crypt(3) hash such as {@code $1$salt$hash} or {@code $6$salt$hash}. */
    CRYPT_HASH("crypt_hash"),
    /** The body of a PEM private key block. */
    PEM_PRIVATE_KEY("pem_private_key");

    private final String id;

    RuleId(String id) {
        this.id = id;
    }

    /** The stable id stored and reported. */
    public String id() {
        return id;
    }

    /** All rule ids in application order. */
    public static List<String> ids() {
        return java.util.Arrays.stream(values()).map(RuleId::id).toList();
    }
}
