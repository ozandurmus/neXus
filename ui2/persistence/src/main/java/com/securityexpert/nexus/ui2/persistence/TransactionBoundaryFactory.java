package com.securityexpert.nexus.ui2.persistence;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Builds a {@link TransactionBoundary} from plain JDBC connection
 * parameters, for a caller (a non-Spring worker main) that must never
 * import {@code org.jooq} itself (DIR-7's spirit, applied here to keep
 * {@code worker}'s own composition roots jOOQ-free even though the
 * ArchUnit rule only names {@code platform-core}/{@code capability-registry}/
 * {@code jobs}). jOOQ auto-detects the SQL dialect from the JDBC URL
 * scheme, so no dialect parameter is needed here.
 */
public final class TransactionBoundaryFactory {

    private TransactionBoundaryFactory() {
    }

    public static TransactionBoundary fromJdbc(String jdbcUrl, String user, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(jdbcUrl);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return new JooqTransactionBoundary(DSL.using(dataSource, SQLDialect.POSTGRES));
    }
}
