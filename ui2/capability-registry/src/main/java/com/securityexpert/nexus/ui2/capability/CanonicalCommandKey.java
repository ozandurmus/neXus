package com.securityexpert.nexus.ui2.capability;

import java.util.Objects;

/**
 * C4 §3.3 step 2: {@code (vendor, platform_role_scope, shell_context,
 * transport_kind, exact command/call template)} -- the literal authored
 * string (or, for {@code xml_api_call}, the {@code {type, category,
 * target_scope}} tuple serialized into {@code commandKey}), never the
 * runtime-substituted value of a captured variable, never a prefix or
 * regex. {@link #equals(Object)} is therefore exact-match only by
 * construction (record equality), which is what makes {@link GateResolver}
 * incapable of a "near-miss" match (contract §7 test 2 / C4 §7 AC 2).
 */
public record CanonicalCommandKey(
        String vendor,
        String platformRoleScope,
        String shellContext,
        String transportKind,
        String commandKey) {

    public CanonicalCommandKey {
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(platformRoleScope, "platformRoleScope");
        Objects.requireNonNull(shellContext, "shellContext");
        Objects.requireNonNull(transportKind, "transportKind");
        Objects.requireNonNull(commandKey, "commandKey");
    }
}
