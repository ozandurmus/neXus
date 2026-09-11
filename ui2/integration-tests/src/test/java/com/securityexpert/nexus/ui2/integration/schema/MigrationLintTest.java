package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Contract §7 test 10 / C1 §9 AC-6 check 10 / this contract's AC-9.
 *
 * <p>Greps {@code V1__initial_schema.sql} for the C1 §8 denylist
 * ({@code SERIAL}/{@code BIGSERIAL}, an unqualified proprietary
 * PostgreSQL-only extension function, {@code pg_catalog}-only syntax) and
 * fails if a construct outside C1 §8's already-named exception table
 * appears. C1 §8 explicitly names, and therefore permits, exactly:
 * {@code GENERATED ALWAYS AS IDENTITY}, {@code TIMESTAMPTZ}, {@code JSONB}
 * (as an opaque payload column only), a PL/pgSQL trigger function,
 * {@code current_setting}/{@code SET LOCAL}, and {@code SECURITY DEFINER}.
 * This is a text-level, container-free test: it never opens a database
 * connection (C1 §9 check 10 itself: "a migration lint step, not
 * necessarily a JUnit test").</p>
 */
class MigrationLintTest {

    private static final List<Pattern> DENYLIST = List.of(
            // SERIAL/BIGSERIAL: C1 §8 uses GENERATED ALWAYS AS IDENTITY
            // instead for the one internal surrogate key this migration has.
            Pattern.compile("\\bSERIAL\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bBIGSERIAL\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bSMALLSERIAL\\b", Pattern.CASE_INSENSITIVE),
            // PostgreSQL-only extension functions not named as an exception
            // anywhere in C1 §8 (identifiers are minted in Java, never by a
            // database-side UUID/crypto extension).
            Pattern.compile("gen_random_uuid\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("uuid_generate_v\\d\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpgcrypto\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\buuid-ossp\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpg_trgm\\b", Pattern.CASE_INSENSITIVE),
            // pg_catalog-only syntax.
            Pattern.compile("pg_catalog\\.", Pattern.CASE_INSENSITIVE),
            // Explicitly ruled out by C1 §8's closing paragraph.
            Pattern.compile("\\bPARTIAL\\s+INDEX\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bLISTEN\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bNOTIFY\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("CREATE\\s+EXTENSION", Pattern.CASE_INSENSITIVE));

    @Test
    void v1DoesNotIntroduceAnUndocumentedPostgresConstruct() {
        String sql = SchemaV1Fixture.migrationText();
        assertFalse(sql.isBlank(), "V1__initial_schema.sql was empty or unreadable");

        List<String> violations = new java.util.ArrayList<>();
        for (Pattern pattern : DENYLIST) {
            if (pattern.matcher(sql).find()) {
                violations.add(pattern.pattern());
            }
        }

        assertTrue(violations.isEmpty(),
                "V1__initial_schema.sql contains a construct not named as an exception in C1 §8: " + violations);
    }

    @Test
    void v1DoesUseTheThreeNamedExceptionsExactlyAsC1Section8Documents() {
        String sql = SchemaV1Fixture.migrationText();

        // Positive assertions: the three C1 §8 "named exception" constructs
        // this migration is expected to use are actually present, so this
        // lint proves the file matches §8's table rather than merely
        // containing no denylisted token by accident (e.g. an empty file
        // would otherwise pass the negative check above vacuously).
        assertTrue(sql.contains("JSONB"), "expected JSONB (C1 §8 named exception) in audit_log's before/after columns");
        assertTrue(sql.contains("SECURITY DEFINER"), "expected SECURITY DEFINER (C1 §8 named exception) on fn_audit_capture");
        assertTrue(sql.contains("current_setting("), "expected current_setting(...) (C1 §8 named exception) in fn_audit_capture");
        assertTrue(sql.contains("GENERATED ALWAYS AS IDENTITY"),
                "expected GENERATED ALWAYS AS IDENTITY (C1 §8, no exception needed) on audit_log.audit_id");
    }
}
