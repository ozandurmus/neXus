package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** PostgreSQL storage for the one first-boot root identity id. */
public final class JooqRootIdentityRepository implements RootIdentityRepository {
    private final TransactionBoundary transactionBoundary;

    public JooqRootIdentityRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public Optional<String> rootLocalIdentityId() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch("select local_identity_id from root_identity")
                .stream().findFirst().map(row -> row.get("local_identity_id", String.class)));
    }

    @Override
    public void recordRootLocalIdentityId(String localIdentityId) {
        transactionBoundary.inTransaction(dsl -> {
            dsl.execute("insert into root_identity(singleton, local_identity_id) values (true, {0})", localIdentityId);
            return null;
        });
    }
}
