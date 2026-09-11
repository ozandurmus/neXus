package com.securityexpert.nexus.ui2.persistence.identity;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.SecurityAdminBootstrapPort;

/**
 * Composes the {@code persistence}-side dependencies for
 * {@link SecurityAdminBootstrapPort} from raw JDBC parameters (adjudication
 * F8). This class is the only place that names an {@code org.jooq} type in
 * the call path {@code cli} → {@code job-engine} → {@code persistence}:
 * {@code job-engine}'s own {@code SecurityAdminBootstrapFactory} calls only
 * this method's public signature, which returns the {@code platform-core}
 * port type — {@code job-engine} never names {@code org.jooq.DSLContext}
 * itself, so its own compile classpath needs no jOOQ dependency added.
 */
public final class SecurityAdminBootstrapComposition {

    private SecurityAdminBootstrapComposition() {
    }

    public static SecurityAdminBootstrapPort create(String jdbcUrl, String user, String password) {
        DSLContext dsl = DSL.using(jdbcUrl, user, password);
        TransactionBoundary transactionBoundary = new JooqTransactionBoundary(dsl);
        RoleBindingRepository roleBindingRepository = new JooqRoleBindingRepository(transactionBoundary);
        return new RoleBindingSecurityAdminBootstrap(roleBindingRepository);
    }
}
