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
        NAME
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

    @Override
    public String toString() {
        return "EstateIdentity[" + kind + "]";
    }
}
