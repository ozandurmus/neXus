package com.securityexpert.nexus.ui2.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * C3B contract (docs/design/UI2_0_C3B_BOOTSTRAP_IDENTITIES_AND_ROLES.md,
 * FROZEN 2026-09-13) AC-8: {@code bootstrap-local-identity} keeps working
 * -- BOOT-1 is additive to it, never a replacement (§2).
 *
 * <p>This module has no database fixture (unlike {@code integration-tests}),
 * so this class proves only the DB-free half: the command is still
 * dispatched by name and still validates its own argument count before
 * touching any database. The DB-backed half -- an actual row created -- is
 * already {@code integration-tests}' concern
 * ({@code LocalCredentialsSchemaTest}) and this environment cannot run it
 * either way (no Docker/UI2_TEST_JDBC_URL here).</p>
 */
class CliEntryPointTest {

    @Test
    void bootstrapLocalIdentityIsStillARecognizedCommandAndValidatesItsArgumentCountBeforeTouchingAnyDatabase() {
        String output = captureStdErr(() -> CliEntryPoint.main(new String[] {"bootstrap-local-identity"}));

        // Reaching this exact message proves the "bootstrap-local-identity"
        // case is still wired in the command switch and still refuses a
        // malformed invocation before ever building a DataSource -- the
        // only way this message is reached without one.
        assertTrue(output.contains("bootstrap-local-identity requires exactly 5 arguments"),
                "expected the existing argument-count refusal message; got: " + output);
    }

    @Test
    void noArgumentsPrintsUsageIncludingBootstrapLocalIdentity() {
        String output = captureStdOut(() -> CliEntryPoint.main(new String[0]));

        assertTrue(output.contains("bootstrap-local-identity"),
                "usage output must still document bootstrap-local-identity; got: " + output);
    }

    /**
     * 13G LIA-2: every local identity administration subcommand is
     * dispatched by name and validates its own argument count before
     * touching any database -- this module has no database fixture, exactly
     * like the existing bootstrap-local-identity coverage above.
     */
    @Test
    void localIdentitySubcommandsAreRecognizedAndValidateTheirArgumentCountBeforeTouchingAnyDatabase() {
        assertTrue(captureStdErr(() -> CliEntryPoint.main(new String[] {"local-identity-create"}))
                .contains("local-identity-create requires exactly 7 arguments"));
        assertTrue(captureStdErr(() -> CliEntryPoint.main(new String[] {"local-identity-list"}))
                .contains("local-identity-list requires exactly 4 arguments"));
        assertTrue(captureStdErr(() -> CliEntryPoint.main(new String[] {"local-identity-set-password"}))
                .contains("local-identity-set-password requires exactly 7 arguments"));
        assertTrue(captureStdErr(() -> CliEntryPoint.main(new String[] {"local-identity-disable"}))
                .contains("local-identity-disable requires exactly 6 arguments"));
        assertTrue(captureStdErr(() -> CliEntryPoint.main(new String[] {"local-identity-enable"}))
                .contains("local-identity-enable requires exactly 6 arguments"));
    }

    /** 13G LIA-3.4: role-bind subcommands validate their own argument count before making any HTTP call. */
    @Test
    void roleBindSubcommandsAreRecognizedAndValidateTheirArgumentCountBeforeAnyHttpCall() {
        assertTrue(captureStdErr(() -> CliEntryPoint.main(new String[] {"role-bind-create"}))
                .contains("role-bind-create requires exactly 6 arguments"));
        assertTrue(captureStdErr(() -> CliEntryPoint.main(new String[] {"role-bind-revoke"}))
                .contains("role-bind-revoke requires exactly 4 arguments"));
    }

    @Test
    void usageDocumentsEveryLocalIdentityAdministrationSubcommand() {
        String output = captureStdOut(() -> CliEntryPoint.main(new String[0]));

        for (String command : new String[] {"local-identity-create", "local-identity-list",
                "local-identity-set-password", "local-identity-disable", "local-identity-enable",
                "role-bind-create", "role-bind-revoke"}) {
            assertTrue(output.contains(command), "usage output must document " + command + "; got: " + output);
        }
    }

    private static String captureStdErr(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static String captureStdOut(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
