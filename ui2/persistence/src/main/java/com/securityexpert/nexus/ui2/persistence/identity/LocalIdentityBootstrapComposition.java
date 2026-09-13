package com.securityexpert.nexus.ui2.persistence.identity;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.LocalIdentityBootstrapPort;

/**
 * Composes the {@code persistence}-side dependencies for
 * {@link LocalIdentityBootstrapPort} from raw JDBC parameters, mirroring
 * {@link SecurityAdminBootstrapComposition} exactly (C3A contract §11 U-2).
 */
public final class LocalIdentityBootstrapComposition {

    private LocalIdentityBootstrapComposition() {
    }

    public static LocalIdentityBootstrapPort create(String jdbcUrl, String user, String password) {
        DSLContext dsl = DSL.using(jdbcUrl, user, password);
        TransactionBoundary transactionBoundary = new JooqTransactionBoundary(dsl);
        LocalCredentialsRepository repository = new JooqLocalCredentialsRepository(transactionBoundary);
        return new LocalIdentityBootstrap(repository);
    }
}
