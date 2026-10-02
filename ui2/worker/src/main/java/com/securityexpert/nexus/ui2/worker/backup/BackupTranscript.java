package com.securityexpert.nexus.ui2.worker.backup;

import java.util.function.Supplier;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

/** Executor decisions remain visible even when a run fails before reaching a transport. */
public final class BackupTranscript {
    private BackupTranscript() {}

    public static BackupResult record(String executor, Supplier<BackupResult> run) {
        note(executor + ": precheck started");
        try {
            BackupResult result = run.get();
            String reason = switch (result) {
                case BackupResult.Completed r -> "backup stored; cleanup completed";
                case BackupResult.CleanupFailed r -> r.reason();
                case BackupResult.Partial r -> "stored with missing part: " + r.missing();
                case BackupResult.DigestMismatch r -> "device and received digests did not match; archive retained";
                case BackupResult.OutcomeUnknown r -> r.reason();
                case BackupResult.CredentialUnresolvable r -> r.reason();
                case BackupResult.ConnectFailed r -> r.reason();
                case BackupResult.InsufficientFreeSpace r -> r.reason();
                case BackupResult.SubmitOutputUnparseable r -> r.reason();
                case BackupResult.SubmitRefused r -> r.reason();
                case BackupResult.ArtefactStoreFailed r -> r.reason();
            };
            switch (result) {
                case BackupResult.Completed r -> stored(r.archiveName(), r.artefact());
                case BackupResult.CleanupFailed r -> stored(r.archiveName(), r.artefact());
                case BackupResult.Partial r -> stored(r.archiveName(), r.artefact());
                default -> { }
            }
            note(executor + ": " + result.getClass().getSimpleName() + ": " + reason);
            return result;
        } catch (RuntimeException e) {
            note(executor + ": unexpected " + e.getClass().getSimpleName());
            throw e;
        }
    }

    public static void note(String text) { JobTranscriptScope.add("backup", "note", text); }

    public static void stored(String filename, ArtefactStore.ArtefactMetadata metadata) {
        JobTranscriptScope.add("backup", "transfer", "stored file=" + filename + " size=" + metadata.plaintextBytes()
                + " sha256=" + metadata.plaintextSha256());
    }
}
