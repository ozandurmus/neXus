package com.securityexpert.nexus.ui2.persistence.audit;

import java.util.List;
import java.util.Optional;

/**
 * Contract §5.3: keyset-paginated, {@code next_cursor} only -- never a total
 * count, never an {@code OFFSET}. {@code entries} never exceeds the
 * server-capped {@code limit} the caller requested (the repository fetches
 * one extra row internally only to compute {@link #nextCursor()}, then
 * discards it from {@link #entries()}).
 */
public record AuditPage(List<AuditLogRow> entries, Optional<Long> nextCursor) {
}
