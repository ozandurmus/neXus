package com.securityexpert.nexus.ui2.service.privacy;

import java.util.List;
import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** V53 {@code pseudonym_registry}: the first claim of a candidate wins, and a raw key keeps its claim. */
public final class JooqPseudonymRegistry implements PseudonymRegistry {

    private final TransactionBoundary transactionBoundary;

    public JooqPseudonymRegistry(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public String claim(String kind, String rawKey, List<String> candidates) {
        return transactionBoundary.inTransaction(dsl -> {
            var existing = dsl.fetchOptional("select pseudonym from pseudonym_registry where kind = {0} and raw_key = {1}", kind, rawKey);
            if (existing.isPresent()) {
                return existing.get().get(0, String.class);
            }
            for (String candidate : candidates) {
                int inserted = dsl.execute("insert into pseudonym_registry(kind, raw_key, pseudonym) values ({0}, {1}, {2}) "
                        + "on conflict do nothing", kind, rawKey, candidate);
                if (inserted == 1) {
                    return candidate;
                }
                var raced = dsl.fetchOptional("select pseudonym from pseudonym_registry where kind = {0} and raw_key = {1}", kind, rawKey);
                if (raced.isPresent()) {
                    return raced.get().get(0, String.class);
                }
            }
            return candidates.get(0);
        });
    }
}
