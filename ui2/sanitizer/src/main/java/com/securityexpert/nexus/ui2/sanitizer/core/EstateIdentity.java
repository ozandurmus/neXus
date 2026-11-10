package com.securityexpert.nexus.ui2.sanitizer.core;

import java.util.Objects;

/**
 * One identity value the service knows, handed to {@link EstateDictionary#build} and dropped after it.
 * {@code pseudonym} is the UI pseudonym of a {@link Kind#NAME} (null for the other kinds). {@link #toString()} never
 * prints the value.
 */
public record EstateIdentity(Kind kind, String value, String pseudonym) {

    /** How an estate identity value's masked form is made. */
    public enum Kind {
        /** A serial or a recorded device identity: {@code SN-} + 10 hex ({@link IdentityTokens#serial}). */
        SERIAL,
        /** A management or interface address (or a host name used as one): the text rules' own replacement. */
        ADDRESS,
        /** A device or cluster name: its UI pseudonym ({@code FW-...}, {@code CLS-...}). */
        NAME,
        /** An administrator name (policy history, {@code last-modifier}): the user token {@code USR-}. */
        USER(IdentityTokens.USER),
        /** A policy, policy-package, layer or device-group name: {@code POL-}. */
        POLICY(IdentityTokens.POLICY),
        /** A rule name: {@code RUL-}. */
        RULE(IdentityTokens.RULE),
        /** A network, service or group object name: {@code OBJ-}. */
        OBJECT(IdentityTokens.OBJECT),
        /** A security zone name: {@code ZON-}. */
        ZONE(IdentityTokens.ZONE),
        /** A virtual-system or virtual-router name: {@code VSN-}. */
        VSYS(IdentityTokens.VSYS),
        /** A Multi-Domain Server domain name: {@code DMN-}. */
        DOMAIN(IdentityTokens.DOMAIN);

        private final String tokenKind;

        Kind() {
            this(null);
        }

        Kind(String tokenKind) {
            this.tokenKind = tokenKind;
        }

        /**
         * True for an estate name ({@code logmask-v5}): matched case-insensitively and as a phrase (single spaces
         * between its words) anywhere in text, and masked as {@code KIND-} + 10 hex ({@link #tokenKind()}).
         */
        public boolean isEstateName() {
            return tokenKind != null;
        }

        /** The {@link IdentityTokens} kind of an estate name; null for the other kinds. */
        public String tokenKind() {
            return tokenKind;
        }
    }

    public EstateIdentity {
        Objects.requireNonNull(kind, "kind");
    }

    public static EstateIdentity serial(String value) {
        return new EstateIdentity(Kind.SERIAL, value, null);
    }

    public static EstateIdentity address(String value) {
        return new EstateIdentity(Kind.ADDRESS, value, null);
    }

    public static EstateIdentity name(String value, String pseudonym) {
        return new EstateIdentity(Kind.NAME, value, pseudonym);
    }

    /** An estate name of {@code kind} ({@link Kind#isEstateName()}). */
    public static EstateIdentity estateName(Kind kind, String value) {
        if (!kind.isEstateName()) throw new IllegalArgumentException("not an estate-name kind: " + kind);
        return new EstateIdentity(kind, value, null);
    }

    @Override
    public String toString() {
        return "EstateIdentity[" + kind + "]";
    }
}
