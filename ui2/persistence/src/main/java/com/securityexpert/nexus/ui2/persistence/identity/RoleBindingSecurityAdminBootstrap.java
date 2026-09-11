package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.Objects;

import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.platform.SecurityAdminBootstrapPort;

/**
 * The {@link SecurityAdminBootstrapPort} implementation (C3 §4.3,
 * adjudication F8): writes the very first {@code role:security_admin}
 * binding directly, attributed to the reserved
 * {@link SecurityAdminBootstrapPort#BOOTSTRAP_ACTOR} marker, itself
 * audited by {@code trg_audit_role_bindings}. Reachable only from a
 * deployment-controlled CLI invocation (the {@code cli} module), never a
 * running service, never the browser.
 */
public final class RoleBindingSecurityAdminBootstrap implements SecurityAdminBootstrapPort {

    private final RoleBindingRepository roleBindingRepository;

    public RoleBindingSecurityAdminBootstrap(RoleBindingRepository roleBindingRepository) {
        this.roleBindingRepository = Objects.requireNonNull(roleBindingRepository, "roleBindingRepository");
    }

    @Override
    public String bootstrapFirstSecurityAdminBinding(byte[] groupReferenceEncrypted, String groupReferenceKeyId) {
        String bindingId = OpaqueId.random().value();
        return roleBindingRepository.create(bindingId, RoleToken.SECURITY_ADMIN.token(),
                groupReferenceEncrypted, groupReferenceKeyId, BOOTSTRAP_ACTOR, "bootstrap_security_admin_binding");
    }
}
