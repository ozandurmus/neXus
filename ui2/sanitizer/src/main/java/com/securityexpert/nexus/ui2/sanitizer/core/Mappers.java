package com.securityexpert.nexus.ui2.sanitizer.core;

import java.util.Objects;

/** The keyed mapping functions of one installation (one key), shared by every job; thread-safe. */
public final class Mappers {

    private final IpPermutation ip;
    private final MacMapper mac;
    private final NameMapper names;
    private final KnownNames known;
    private final IdentityTokens identity;

    public Mappers(SanitizerKey key, KnownNames known) {
        this(IpPermutation.nexus(key), MacMapper.nexus(key), new NameMapper(key), known, key.identityTokens());
    }

    /** Without identity tokens: the text rules run without the serial rule. */
    public Mappers(IpPermutation ip, MacMapper mac, NameMapper names, KnownNames known) {
        this(ip, mac, names, known, null);
    }

    public Mappers(IpPermutation ip, MacMapper mac, NameMapper names, KnownNames known, IdentityTokens identity) {
        this.ip = Objects.requireNonNull(ip, "ip");
        this.mac = Objects.requireNonNull(mac, "mac");
        this.names = Objects.requireNonNull(names, "names");
        this.known = known == null ? KnownNames.NONE : known;
        this.identity = identity;
    }

    public IpPermutation ip() {
        return ip;
    }

    public MacMapper mac() {
        return mac;
    }

    public NameMapper names() {
        return names;
    }

    public KnownNames known() {
        return known;
    }

    /** The one-way identity tokens ({@code SN-}, {@code USR-}) under the same key, or null when not set. */
    public IdentityTokens identity() {
        return identity;
    }
}
