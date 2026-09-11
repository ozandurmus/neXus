package com.securityexpert.nexus.ui2.service.security;

import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * A closed action-registry entry, standing in for {@code C4}'s eventual
 * registry (deferred, contract §2's "explicitly deferred" list: "the
 * capability registry and {@code action_id} shape... treated here as an
 * opaque string with a required role token or none"). This module seeds
 * only the actions this movement itself introduces
 * ({@link ActionRegistry#seedActions()}); it is not C4's registry and does
 * not claim to be.
 *
 * @param consoleSubmittable {@code false} means {@code ActionClass} 1: {@code E3}
 *                            refuses it unconditionally, every role (C3 §6.1)
 * @param requiredRoleToken   {@code empty} means {@code NO_APPLICABLE_AUTHORITY}
 *                             (open to any authenticated session) at {@code E4}
 */
public record ActionDescriptor(String actionId, boolean consoleSubmittable, Optional<RoleToken> requiredRoleToken) {
}
