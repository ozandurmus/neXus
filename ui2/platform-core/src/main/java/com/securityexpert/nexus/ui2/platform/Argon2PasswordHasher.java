package com.securityexpert.nexus.ui2.platform;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * The Argon2id verifier (C3A contract §3.1/§3.2): a one-way, memory-hard
 * password hash, never a reversible form. BouncyCastle is this movement's
 * own choice of concrete library (§11 U-1 is not fixed by the contract);
 * {@link #DEFAULT_PARAMETERS} is this movement's own current default, not a
 * contract-fixed value -- an existing row's own recorded {@link Parameters}
 * (never this constant) is what {@link #verify} uses for that row, which is
 * what lets the default move up later without invalidating existing
 * credentials (§3.2).
 */
public final class Argon2PasswordHasher {

    public static final String ALGORITHM_ID = "argon2id";

    private static final int VERIFIER_LENGTH_BYTES = 32;
    private static final int SALT_LENGTH_BYTES = 16;

    /** OWASP's second recommended argon2id option (m=19 MiB, t=2, p=1) -- this movement's own pick, §11 U-1. */
    public static final Parameters DEFAULT_PARAMETERS = new Parameters(19_456, 2, 1);

    private static final SecureRandom RANDOM = new SecureRandom();

    private Argon2PasswordHasher() {
    }

    /** Recorded per-row (§3.2), never fixed globally in code for an existing row. */
    public record Parameters(int memoryCostKib, int timeCost, int parallelism) {
    }

    /** Everything a row needs to verify a future attempt against it (§3.2): never a recoverable password. */
    public record Verifier(byte[] verifier, byte[] salt, String algorithmId, Parameters parameters) {
    }

    /** Hashes a new credential under the given parameters, with a fresh, per-credential salt (§3.2). */
    public static Verifier hash(char[] password, Parameters parameters) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        RANDOM.nextBytes(salt);
        return new Verifier(derive(password, salt, parameters), salt, ALGORITHM_ID, parameters);
    }

    /**
     * Verifies a submitted password against a stored verifier, reading the
     * verification parameters from {@code stored} itself -- never from
     * {@link #DEFAULT_PARAMETERS} -- so a row keeps verifying correctly even
     * after this class's own default parameters move up (§3.2, test 3).
     */
    public static boolean verify(char[] password, Verifier stored) {
        Objects.requireNonNull(stored, "stored");
        byte[] candidate = derive(password, stored.salt(), stored.parameters());
        try {
            // MessageDigest.isEqual is documented to run in time that does
            // not depend on the content of the arrays it is given, for
            // equal-length inputs (both are the fixed VERIFIER_LENGTH_BYTES
            // here) -- the equality check itself is not the timing signal
            // this contract's §5.3 is concerned with; the surrounding
            // Argon2 computation is (see LocalMechanism).
            return MessageDigest.isEqual(candidate, stored.verifier());
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
    }

    private static byte[] derive(char[] password, byte[] salt, Parameters parameters) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(parameters.memoryCostKib())
                .withIterations(parameters.timeCost())
                .withParallelism(parameters.parallelism())
                .withSalt(salt)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] output = new byte[VERIFIER_LENGTH_BYTES];
        byte[] passwordBytes = toUtf8Bytes(password);
        try {
            generator.generateBytes(passwordBytes, output);
            return output;
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
        }
    }

    /** Converts a password directly from a {@code char[]} to UTF-8 bytes, never allocating a {@code String}. */
    private static byte[] toUtf8Bytes(char[] password) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
        return bytes;
    }
}
