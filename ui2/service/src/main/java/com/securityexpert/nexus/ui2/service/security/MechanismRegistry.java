package com.securityexpert.nexus.ui2.service.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.Mechanism;

/**
 * The closed, repository-committed mechanism registry (C3A contract §2.1).
 *
 * <p><b>Why {@code local} is structurally non-removable (AC-2):</b> the
 * constructor's first parameter is typed {@link LocalMechanism}, not
 * {@code Mechanism} and not {@code Optional<Mechanism>} -- there is no
 * constructor overload, configuration flag, or code path that can produce a
 * {@link MechanismRegistry} without one. Adding a further mechanism (e.g.
 * {@code ldap}, or a later {@code radius}/{@code tacacs}) is passing an
 * additional {@link Mechanism} in the {@code additional} list, registered
 * under its own {@link Mechanism#mechanismId()} -- never a change to this
 * class's shape or any caller of {@link #find}.</p>
 */
public final class MechanismRegistry {

    private final Map<String, Mechanism> mechanisms;

    public MechanismRegistry(LocalMechanism local, List<Mechanism> additional) {
        Objects.requireNonNull(local, "local");
        Map<String, Mechanism> byId = new LinkedHashMap<>();
        byId.put(local.mechanismId(), local);
        for (Mechanism mechanism : additional) {
            byId.put(mechanism.mechanismId(), mechanism);
        }
        this.mechanisms = Map.copyOf(byId);
    }

    public Optional<Mechanism> find(String mechanismId) {
        if (mechanismId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mechanisms.get(mechanismId));
    }

    public boolean isRegistered(String mechanismId) {
        if (mechanismId == null) {
            return false;
        }
        return mechanisms.containsKey(mechanismId);
    }
}
