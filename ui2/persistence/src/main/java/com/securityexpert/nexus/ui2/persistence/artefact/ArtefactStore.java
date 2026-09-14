package com.securityexpert.nexus.ui2.persistence.artefact;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The configuration-collection artefact store (14G CG-9, 13F CF-2, C7
 * section 4 minimum viable form -- see {@link
 * com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher}'s own class
 * comment for what "minimum" leaves out). Every raw configuration read is
 * written here, encrypted at rest, gzip-compressed when the caller asks for
 * it, and identified afterward only by the opaque {@link ArtefactRef} this
 * store returns -- never a database row for the plaintext bytes themselves
 * (AGENTS.md raw-evidence law; C7 section 1.4: "a backup artefact... never
 * a database row").
 *
 * <p>{@link #open} streams: the caller writes plaintext bytes to {@link
 * ArtefactHandle#sink()} as it reads them from the device (14G CG-5: never
 * held whole in memory), and calls {@link ArtefactHandle#finish()} once
 * every byte has been written. This lets one XML-streaming pass (Palo
 * Alto's {@code effective-running}) both parse the document and pipe its
 * raw bytes through this store in a single read of the transport's
 * response.</p>
 */
public interface ArtefactStore {

    ArtefactHandle open(String deviceId, String jobId, String vendor, boolean gzip) throws IOException;

    /** Decrypts (and, if {@code gzip} was true at write time, decompresses) the artefact back into a stream. */
    InputStream retrieve(ArtefactRef ref, boolean gzip) throws IOException;

    interface ArtefactHandle extends Closeable {

        /** Write plaintext bytes here, in any number of calls. */
        OutputStream sink();

        /** Closes {@link #sink()} and returns the recorded metadata. Call exactly once, after every byte is written. */
        ArtefactMetadata finish() throws IOException;
    }

    record ArtefactMetadata(
            ArtefactRef ref,
            String plaintextSha256,
            long plaintextBytes,
            String ciphertextSha256,
            long ciphertextBytes,
            String compression,
            String keyId) {
    }
}
