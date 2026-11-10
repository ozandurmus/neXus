package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

/** What a parser made of an answer (the first three outcomes of docs/design/LIFECYCLE_LICENSE_STORAGE_CONTRACT.md section 2). */
public enum ParseOutcome {
    /** At least one item (or, for {@code cplp list}, a recognised header). */
    COLLECTED,
    /** A blank answer, or one that is understood but names nothing (the stored items are then kept). */
    EMPTY,
    /** An answer without any recognisable line. */
    UNPARSED
}
