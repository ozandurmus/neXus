package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.List;
import java.util.Optional;

/**
 * {@code role_bindings} persistence port (contract §3 placement row).
 * Every mutation requires {@code role:security_admin} and the self-grant
 * check (C3 §4.3) — enforced by the service-layer caller, not this
 * interface; this port only ever performs the write it is asked to.
 */
public interface RoleBindingRepository {

    List<RoleBindingRecord> findActiveByToken(String roleToken);

    Optional<RoleBindingRecord> find(String bindingId);

    boolean hasAnyActiveBinding(String roleToken);

    String create(String bindingId, String roleToken, byte[] groupReferenceEncrypted, String groupReferenceKeyId,
            String createdByActorFingerprint, String actionId);

    void revoke(String bindingId, String revokedByActorFingerprint, String actionId);
}
