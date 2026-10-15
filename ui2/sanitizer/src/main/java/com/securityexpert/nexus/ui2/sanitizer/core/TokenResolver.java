package com.securityexpert.nexus.ui2.sanitizer.core;

import java.util.Collection;
import java.util.Map;

/**
 * The revert side of the vault: answers, for a batch of candidate tokens found in a returned file, the originals of
 * those that are in the vault. Candidates not in the vault are simply absent from the answer and stay as they are,
 * so a value an external tool invented is never mapped to a real one.
 */
public interface TokenResolver {

    Map<VaultKey, String> resolve(Collection<VaultKey> candidates);
}
