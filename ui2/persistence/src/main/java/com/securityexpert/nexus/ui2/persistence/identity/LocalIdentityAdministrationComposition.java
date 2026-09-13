package com.securityexpert.nexus.ui2.persistence.identity;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.LocalIdentityAdministrationPort;

/**
 * Composes the {@code persistence}-side dependencies for
 * {@link LocalIdentityAdministrationPort} from raw JDBC parameters,
 * mirroring {@link LocalIdentityBootstrapComposition} exactly (13G
 * {@code LIA-2}): {@code cli} depends on {@code job-engine} rather than on
 * {@code persistence} directly, so a {@code job-engine} factory calls this
 * class on the CLI's behalf.
 */
public final class LocalIdentityAdministrationComposition {

    private LocalIdentityAdministrationComposition() {
    }

    public static LocalIdentityAdministrationPort create(String jdbcUrl, String user, String password,
            String groupReferenceKeyBase64) {
        DSLContext dsl = DSL.using(jdbcUrl, user, password);
        TransactionBoundary transactionBoundary = new JooqTransactionBoundary(dsl);
        LocalCredentialsRepository localCredentialsRepository = new JooqLocalCredentialsRepository(transactionBoundary);
        SessionRepository sessionRepository = new JooqSessionRepository(transactionBoundary);
        RoleBindingRepository roleBindingRepository = new JooqRoleBindingRepository(transactionBoundary);
        GroupReferenceCipher groupReferenceCipher = GroupReferenceCipher.fromBase64Key(groupReferenceKeyBase64);
        SecurityAdminLockoutGuard securityAdminLockoutGuard =
                new SecurityAdminLockoutGuard(roleBindingRepository, localCredentialsRepository, groupReferenceCipher);
        return new LocalIdentityAdministration(localCredentialsRepository, sessionRepository, securityAdminLockoutGuard);
    }
}
