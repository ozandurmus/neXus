package com.securityexpert.nexus.ui2.sanitizer;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.securityexpert.nexus.ui2.sanitizer.core.TokenResolver;
import com.securityexpert.nexus.ui2.sanitizer.core.TokenSink;
import com.securityexpert.nexus.ui2.sanitizer.core.VaultKey;

/** An in-memory mapping vault for tests: first original per key wins, like the database vault. */
public final class TestVault implements TokenSink, TokenResolver {

    public final Map<VaultKey, String> entries = new LinkedHashMap<>();
    public int lookups;

    @Override
    public void record(VaultKey key, String original) {
        entries.putIfAbsent(key, original);
    }

    @Override
    public Map<VaultKey, String> resolve(Collection<VaultKey> candidates) {
        lookups++;
        Map<VaultKey, String> out = new HashMap<>();
        for (VaultKey k : candidates) {
            String v = entries.get(k);
            if (v != null) out.put(k, v);
        }
        return out;
    }

    public static byte[] key() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) (0x40 + i);
        return key;
    }
}
