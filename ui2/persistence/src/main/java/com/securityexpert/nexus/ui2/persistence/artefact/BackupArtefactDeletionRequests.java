package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * The V70 deletion requests for backups whose device no longer exists: read the open ones, close one with an outcome,
 * and remove an orphan's manifest row and the rows pointing at it -- each write audited under the requester.
 */
public final class BackupArtefactDeletionRequests {

    public static final String ACTION_ID = "device_backup_orphan_delete";

    public record Pending(String artefactId, String requester, Optional<String> recoveryVolumePath, boolean deviceExists) {
    }

    private final TransactionBoundary tx;

    public BackupArtefactDeletionRequests(TransactionBoundary tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    public List<Pending> open() {
        return tx.inTransaction(dsl -> dsl.fetch(
                "select r.artefact_id, r.requested_by_actor_fingerprint as actor, a.recovery_volume_path as path, "
                        + "exists (select 1 from devices d where d.device_id = a.device_id) as device_exists "
                        + "from backup_artefact_deletion_request r left join backup_artefact a on a.artefact_id = r.artefact_id "
                        + "where r.completed_at is null").stream()
                .map(r -> new Pending(r.get("artefact_id", String.class), r.get("actor", String.class),
                        Optional.ofNullable(r.get("path", String.class)), Boolean.TRUE.equals(r.get("device_exists", Boolean.class))))
                .toList());
    }

    public void close(String artefactId, String requester, String outcome) {
        new AuditedTransactionBoundary(tx).inTransaction(requester, ACTION_ID, dsl -> dsl.execute(
                "update backup_artefact_deletion_request set completed_at = now(), outcome = {1} where artefact_id = {0}",
                artefactId, outcome));
    }

    /** Removes the orphan's manifest row and its listing/entry/baseline rows, and closes the request as removed. */
    public void removeRows(String artefactId, String requester) {
        new AuditedTransactionBoundary(tx).inTransaction(requester, ACTION_ID, dsl -> {
            dsl.execute("delete from backup_artefact_content_listing where artefact_id = {0}", artefactId);
            dsl.execute("delete from backup_artefact_entry where artefact_id = {0}", artefactId);
            dsl.execute("delete from backup_baseline where artefact_id = {0}", artefactId);
            dsl.execute("delete from backup_artefact where artefact_id = {0} and not exists "
                    + "(select 1 from devices d where d.device_id = backup_artefact.device_id)", artefactId);
            return dsl.execute("update backup_artefact_deletion_request set completed_at = now(), outcome = 'removed' "
                    + "where artefact_id = {0}", artefactId);
        });
    }
}
