package com.securityexpert.nexus.ui2.persistence.artefact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class JooqRetentionQueriesLivenessTest {

    @Test
    void thePredicateNamesTheCallersAliasAndOnlyTheRemovedEvent() {
        assertEquals(" and not exists (select 1 from artefact_retention_ledger l where l.artefact_id = b.artefact_id "
                + "and l.event = 'removed')", JooqRetentionQueries.andNotRemoved("b"));
    }

    @Test
    void anAliasThatIsNotAPlainIdentifierOrWouldBeShadowedIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> JooqRetentionQueries.andNotRemoved(null));
        assertThrows(IllegalArgumentException.class, () -> JooqRetentionQueries.andNotRemoved("a; drop"));
        assertThrows(IllegalArgumentException.class, () -> JooqRetentionQueries.andNotRemoved("A"));
        // the ledger is "l" inside the predicate: an outer "l" would compare the ledger row with itself
        assertThrows(IllegalArgumentException.class, () -> JooqRetentionQueries.andNotRemoved("l"));
    }
}
