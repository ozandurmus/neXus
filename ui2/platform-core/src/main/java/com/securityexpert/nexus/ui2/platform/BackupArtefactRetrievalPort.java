package com.securityexpert.nexus.ui2.platform;

/**
 * 14I OR-1..OR-5: the operator retrieves a backup/configuration artefact
 * through the product's own command line, on the host that holds it --
 * never through an HTTP response (OR-1: "no artefact byte, path or decrypt
 * affordance ever reaches an HTTP response"). {@code ui2/cli} and any
 * future in-process caller share this exact interface and its one
 * implementation, mirroring {@link CredentialStorePort}'s own pattern.
 */
public interface BackupArtefactRetrievalPort {

    sealed interface RetrieveResult {

        /** OR-4: the retrieved file is plaintext from this moment; the product copies it nowhere and retains no destination. */
        record Ok(String destinationPath) implements RetrieveResult {
        }

        record ArtefactNotFound() implements RetrieveResult {
        }

        /** OR-2: {@code role:backup_admin} was not held (unbound token, stale/missing authz state, or the actor's group set does not intersect the bound one). */
        record RoleRefused() implements RetrieveResult {
        }

        /** OR-2: a reason of at least eight characters is required. */
        record ReasonTooShort() implements RetrieveResult {
        }

        record IoFailure(String reason) implements RetrieveResult {
        }
    }

    /**
     * @param actorFingerprint the acting admin's own fingerprint (never a directory group, never a secret)
     * @param artefactId       the {@code backup_artefact.artefact_id} to retrieve
     * @param destinationPath  the exact, operator-named local path to decrypt to -- and nowhere else
     * @param reason           OR-2/OR-3: recorded on the audit row, at least eight characters
     */
    RetrieveResult retrieve(String actorFingerprint, String artefactId, String destinationPath, String reason);
}
