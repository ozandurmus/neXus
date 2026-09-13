package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.platform.SecurityAdminBootstrapPort;

/**
 * BOOT-5a (C3B contract §2): in the same transaction that creates a
 * bootstrap local identity, creates its role binding(s). Per C3A §7.1, a
 * local identity's binding is direct and self-referencing:
 * {@code group_reference_encrypted} holds this identity's own
 * {@code local_identity_id}, encrypted under the same envelope C3 §4.2
 * specifies, rather than a directory group.
 *
 * <p>Atomicity is jOOQ's own nested-transaction (savepoint) behaviour: the
 * {@code dsl.transactionResult(...)} each of {@link LocalCredentialsRepository#create}
 * and {@link RoleBindingRepository#create} already performs internally
 * participates in the single outer transaction {@link #seed} opens here
 * (same underlying {@link TransactionBoundary}/jOOQ {@code Configuration}),
 * rather than each committing independently -- this is why {@link #seed}
 * takes the repositories rather than duplicating their SQL.</p>
 *
 * <p>Distinct from {@link LocalIdentityBootstrap} and
 * {@link RoleBindingSecurityAdminBootstrap} (the deployment-controlled CLI
 * bootstrap path, BOOT-5b): those stay exactly as they are and remain the
 * way a binding is created or changed outside first boot. This class exists
 * only because BOOT-5a requires the identity row and its binding row(s) to
 * commit together: two separate transactions could otherwise leave an
 * identity seeded with no binding after a crash between them, which a
 * restart would never repair, since {@code local_credentials} would no
 * longer be empty (BOOT-2's gate).</p>
 */
public final class FirstBootIdentityRoleBindingSeeder {

    /** BOOT-4: the action id every first-boot role_bindings row is audited under. */
    static final String ACTION_FIRST_BOOT_ROLE_BINDING_CREATE = "first_boot_role_binding_seed";

    private final TransactionBoundary transactionBoundary;
    private final LocalCredentialsRepository localCredentialsRepository;
    private final RoleBindingRepository roleBindingRepository;
    private final GroupReferenceCipher groupReferenceCipher;
    private final String groupReferenceKeyId;

    public FirstBootIdentityRoleBindingSeeder(TransactionBoundary transactionBoundary,
            LocalCredentialsRepository localCredentialsRepository, RoleBindingRepository roleBindingRepository,
            GroupReferenceCipher groupReferenceCipher, String groupReferenceKeyId) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.localCredentialsRepository = Objects.requireNonNull(localCredentialsRepository, "localCredentialsRepository");
        this.roleBindingRepository = Objects.requireNonNull(roleBindingRepository, "roleBindingRepository");
        this.groupReferenceCipher = Objects.requireNonNull(groupReferenceCipher, "groupReferenceCipher");
        this.groupReferenceKeyId = Objects.requireNonNull(groupReferenceKeyId, "groupReferenceKeyId");
    }

    /** One identity, its initial password, and the role tokens bound to it at first boot. */
    public record IdentitySpec(String localIdentityName, char[] initialPassword, List<RoleToken> roleTokens) {
    }

    /**
     * Seeds every identity and every one of its role bindings in a single
     * transaction (BOOT-5a/AC-3). The caller is responsible for the BOOT-2
     * gate ({@code local_credentials} must already be known empty) -- this
     * method always writes what it is given.
     */
    public void seed(List<IdentitySpec> specs) {
        try {
            transactionBoundary.inTransaction(dsl -> {
                for (IdentitySpec spec : specs) {
                    String localIdentityId = OpaqueId.random().value();
                    Argon2PasswordHasher.Verifier verifier = Argon2PasswordHasher.hash(spec.initialPassword(),
                            Argon2PasswordHasher.DEFAULT_PARAMETERS);
                    localCredentialsRepository.create(localIdentityId, spec.localIdentityName(), verifier,
                            SecurityAdminBootstrapPort.BOOTSTRAP_ACTOR);

                    byte[] selfReferenceEncrypted = groupReferenceCipher.encrypt(localIdentityId);
                    for (RoleToken roleToken : spec.roleTokens()) {
                        roleBindingRepository.create(OpaqueId.random().value(), roleToken.token(),
                                selfReferenceEncrypted, groupReferenceKeyId, SecurityAdminBootstrapPort.BOOTSTRAP_ACTOR,
                                ACTION_FIRST_BOOT_ROLE_BINDING_CREATE);
                    }
                }
                return null;
            });
        } finally {
            for (IdentitySpec spec : specs) {
                Arrays.fill(spec.initialPassword(), '\0');
            }
        }
    }
}
