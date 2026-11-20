package com.securityexpert.nexus.ui2.platform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Environment alias of each declared credential reference ({@code NEXUS_CRED_<ALIAS>_USERNAME} /
 * {@code NEXUS_CRED_<ALIAS>_SECRET}), {@code SCRIPT_EXECUTION_CONTRACT.md} Amendment 2026-11-18 (b) item 10.
 *
 * <p>The alias is the display name (or the reference id when it has none), upper-cased with every run of characters
 * outside {@code [A-Z0-9]} turned into one {@code _}. Two references that map to the same alias are never refused (PO
 * decision 2026-11-18: warnings, not refusals): the second gets {@code _2}, the third {@code _3}, in declaration order,
 * and a warning names the collision. The same function runs at save time (for the warning) and at run time (for the
 * names), so a script sees exactly the aliases the screen showed.</p>
 */
public final class ScriptCredentialAliases {

    private ScriptCredentialAliases() {
    }

    /** One declared reference: its id and display name (blank when it has none). */
    public record DeclaredRef(String refId, String displayName) {
        public DeclaredRef {
            Objects.requireNonNull(refId, "refId");
            displayName = displayName == null ? "" : displayName;
        }
    }

    /** Alias per reference id (declaration order) and the collision warnings. */
    public record Assignment(Map<String, String> aliasByRef, List<String> warnings) {
        public Assignment {
            aliasByRef = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(aliasByRef));
            warnings = List.copyOf(warnings);
        }
    }

    public static Assignment assign(List<DeclaredRef> refs) {
        Map<String, String> aliases = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (DeclaredRef ref : refs == null ? List.<DeclaredRef>of() : refs) {
            if (aliases.containsKey(ref.refId())) {
                continue;
            }
            String base = toEnvAlias(ref.displayName().isBlank() ? ref.refId() : ref.displayName());
            String alias = base;
            int n = 2;
            while (taken.contains(alias)) {
                alias = base + "_" + n++;
            }
            if (!alias.equals(base)) {
                warnings.add("Credential reference '" + ref.refId() + "' maps to the same variable name as an earlier one ("
                        + "NEXUS_CRED_" + base + "_*); it is available as NEXUS_CRED_" + alias + "_USERNAME / _SECRET.");
            }
            taken.add(alias);
            aliases.put(ref.refId(), alias);
        }
        return new Assignment(aliases, warnings);
    }

    /** Upper-case {@code [A-Z0-9_]+}; a leading digit gets a {@code CRED_} prefix; blank becomes {@code DEFAULT}. */
    public static String toEnvAlias(String raw) {
        if (raw == null || raw.isBlank()) {
            return "DEFAULT";
        }
        String normalized = raw.strip()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isEmpty()) {
            return "DEFAULT";
        }
        return Character.isDigit(normalized.charAt(0)) ? "CRED_" + normalized : normalized;
    }
}
