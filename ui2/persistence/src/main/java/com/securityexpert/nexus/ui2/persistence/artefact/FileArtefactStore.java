package com.securityexpert.nexus.ui2.persistence.artefact;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.spec.SecretKeySpec;

import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;

/**
 * Filesystem-backed {@link ArtefactStore} (C7 section 4 minimum viable
 * form): {@code <root>/<vendor>/<device_id>/<artefact_ref>.enc}, content
 * addressed by the ciphertext's own SHA-256 (C7 section 3.2's pattern:
 * "{@code artefact_id}... sha256-of-ciphertext identity as value").
 *
 * <p>Write pipeline (14G CG-5: streamed end to end, never one in-memory
 * buffer): the caller's bytes are digested (plaintext hash), optionally
 * gzip-compressed, encrypted ({@link ArtefactStoreCipher#encryptingStream}),
 * digested again (ciphertext hash), and written straight to a temp file;
 * {@link ArtefactStore.ArtefactHandle#finish()} closes that chain and
 * atomically moves the temp file to its final, content-addressed path.</p>
 */
public final class FileArtefactStore implements ArtefactStore {

    private final Path root;
    private final ArtefactStoreCipher cipher;

    public FileArtefactStore(Path root, ArtefactStoreCipher cipher) {
        this.root = Objects.requireNonNull(root, "root");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("could not create artefact store root: " + root, e);
        }
    }

    @Override
    public ArtefactHandle open(String deviceId, String jobId, String vendor, boolean gzip) throws IOException {
        Path vendorDir = root.resolve(safeSegment(vendor)).resolve(safeSegment(deviceId));
        Files.createDirectories(vendorDir);
        Path tempFile = Files.createTempFile(vendorDir, "artefact-", ".tmp");
        return new FileArtefactHandle(tempFile, vendorDir, gzip);
    }

    @Override
    public InputStream retrieve(ArtefactRef ref, byte[] wrappedDataKey, boolean gzip) throws IOException {
        Path path = pathFor(ref);
        SecretKeySpec dataKey = cipher.unwrapKey(wrappedDataKey);
        InputStream decrypted = cipher.decryptingStream(Files.newInputStream(path), dataKey);
        return gzip ? new GZIPInputStream(decrypted) : decrypted;
    }

    private Path pathFor(ArtefactRef ref) {
        // ref.value() is "<vendor>/<device_id>/<sha256hex>" -- see FileArtefactHandle.finish().
        return root.resolve(ref.value());
    }

    private static String safeSegment(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private final class FileArtefactHandle implements ArtefactHandle {

        private final Path tempFile;
        private final Path vendorDeviceDir;
        private final boolean gzip;
        private final SecretKeySpec dataKey;
        private final CountingDigestOutputStream plaintextCounter;
        private final CountingDigestOutputStream ciphertextCounter;
        private final OutputStream sink;
        private boolean finished;

        FileArtefactHandle(Path tempFile, Path vendorDeviceDir, boolean gzip) throws IOException {
            this.tempFile = tempFile;
            this.vendorDeviceDir = vendorDeviceDir;
            this.gzip = gzip;
            // BK-15: a fresh, random data key per artefact -- the master key
            // never encrypts artefact bytes directly.
            this.dataKey = cipher.generateDataKey();
            OutputStream fileOut = Files.newOutputStream(tempFile);
            this.ciphertextCounter = new CountingDigestOutputStream(fileOut);
            OutputStream cipherOut = cipher.encryptingStream(ciphertextCounter, dataKey);
            OutputStream compressOut = gzip ? new GZIPOutputStream(cipherOut) : cipherOut;
            this.plaintextCounter = new CountingDigestOutputStream(compressOut);
            this.sink = plaintextCounter;
        }

        @Override
        public OutputStream sink() {
            return sink;
        }

        @Override
        public ArtefactMetadata finish() throws IOException {
            if (finished) {
                throw new IllegalStateException("finish() already called on this artefact handle");
            }
            finished = true;
            sink.close(); // closes the whole chain: gzip finish -> cipher doFinal (GCM tag) -> file close
            String ciphertextSha256 = ciphertextCounter.digestHex();
            Path finalPath = vendorDeviceDir.resolve(ciphertextSha256 + ".enc");
            Files.move(tempFile, finalPath, StandardCopyOption.REPLACE_EXISTING);
            ArtefactRef ref = new ArtefactRef(root.relativize(finalPath).toString());
            return new ArtefactMetadata(ref, plaintextCounter.digestHex(), plaintextCounter.count(), ciphertextSha256,
                    ciphertextCounter.count(), gzip ? "gzip" : "none", cipher.keyId(), cipher.wrapKey(dataKey));
        }

        @Override
        public void close() throws IOException {
            if (!finished) {
                sink.close();
                Files.deleteIfExists(tempFile);
            }
        }
    }

    /** Counts bytes written and digests them (SHA-256) in one pass -- no second read of the data. */
    private static final class CountingDigestOutputStream extends FilterOutputStream {

        private final MessageDigest digest;
        private long count;

        CountingDigestOutputStream(OutputStream out) {
            super(wrapWithDigest(out, newSha256()));
            this.digest = ((DigestOutputStream) this.out).getMessageDigest();
        }

        private static OutputStream wrapWithDigest(OutputStream out, MessageDigest digest) {
            return new DigestOutputStream(out, digest);
        }

        private static MessageDigest newSha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        long count() {
            return count;
        }

        String digestHex() {
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }
}
