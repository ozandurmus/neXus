package com.securityexpert.nexus.ui2.sanitizer.core;

/**
 * Receives every token a sanitize run emits together with the original it replaced -- the input of the mapping
 * vault. Implementations must never log or print either value. The same pair may be offered many times.
 */
public interface TokenSink {

    TokenSink DISCARD = (key, original) -> { };

    void record(VaultKey key, String original);
}
