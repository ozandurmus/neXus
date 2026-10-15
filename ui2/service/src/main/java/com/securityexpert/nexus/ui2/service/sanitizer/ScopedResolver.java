package com.securityexpert.nexus.ui2.service.sanitizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import com.securityexpert.nexus.ui2.persistence.sanitizer.SanitizerRepository;
import com.securityexpert.nexus.ui2.sanitizer.core.TokenResolver;
import com.securityexpert.nexus.ui2.sanitizer.core.VaultKey;

/**
 * The read side of the vault for one revert: looks a batch of candidate tokens up by their HMAC keys, within the
 * revert's scope (the actor's own jobs, one named job, or -- security_admin -- every unexpired vault), and unseals the
 * newest job's original for each. Data keys are unwrapped once per job.
 */
final class ScopedResolver implements TokenResolver {

    private static final int CHUNK = 5000;

    private final SanitizerRepository repository;
    private final VaultCrypto crypto;
    private final String createdBy;
    private final String jobId;
    private final Map<String, SecretKeySpec> keys = new HashMap<>();

    ScopedResolver(SanitizerRepository repository, VaultCrypto crypto, String createdBy, String jobId) {
        this.repository = repository;
        this.crypto = crypto;
        this.createdBy = createdBy;
        this.jobId = jobId;
    }

    @Override
    public Map<VaultKey, String> resolve(Collection<VaultKey> candidates) {
        Map<String, VaultKey> byLookup = new HashMap<>();
        List<byte[]> lookups = new ArrayList<>();
        for (VaultKey candidate : candidates) {
            byte[] lookup = crypto.lookupKey(candidate);
            if (byLookup.putIfAbsent(HexFormat.of().formatHex(lookup), candidate) == null) lookups.add(lookup);
        }
        Map<VaultKey, String> out = new HashMap<>();
        for (int from = 0; from < lookups.size(); from += CHUNK) {
            List<byte[]> chunk = lookups.subList(from, Math.min(lookups.size(), from + CHUNK));
            for (SanitizerRepository.Found found : repository.lookup(chunk, createdBy, jobId)) {
                VaultKey key = byLookup.get(HexFormat.of().formatHex(found.lookupKey()));
                if (key == null || out.containsKey(key)) continue; // newest job first: the first answer wins
                SecretKeySpec dataKey = keys.computeIfAbsent(found.jobId(), id -> crypto.unwrap(found.wrappedDataKey()));
                out.put(key, crypto.unseal(dataKey, found.jobId(), found.lookupKey(), found.sealedOriginal()));
            }
        }
        return out;
    }
}
