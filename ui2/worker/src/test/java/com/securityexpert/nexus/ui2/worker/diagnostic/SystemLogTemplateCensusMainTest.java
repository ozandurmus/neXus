package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.platform.SystemLogCensusFeed;

/** The entry point's arguments, output file and exit codes over a fake provider. */
class SystemLogTemplateCensusMainTest {
    private static final String[] BASE = {"--from", "2026-11-01", "--to", "2026-11-30", "--sources", "cp_messages"};

    private static SystemLogCensusFeed.Provider provider(List<String> lines) {
        return (leak, progress) -> new SystemLogCensusFeed() {
            @Override
            public void chunks(LocalDate from, LocalDate to, List<String> sources, Sink sink) throws Exception {
                sink.accept(new Chunk("c1", "dev-1", "cp_messages", "2026-11-24", "ref", new byte[] {1}, 10));
            }

            @Override
            public Masked read(Chunk chunk, int maxBytes) {
                return new Masked(lines, 0, false);
            }

            @Override
            public String devicePseudonym(String deviceId) {
                return "FW-TANGO-04";
            }

            @Override
            public String maskRuleSet() {
                return "logmask-test";
            }

            @Override
            public void close() {
            }
        };
    }

    private static PrintStream sink(ByteArrayOutputStream b) {
        return new PrintStream(b, true, StandardCharsets.UTF_8);
    }

    @Test
    void reportGoesToStdoutWithoutOut(@TempDir Path root) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SystemLogTemplateCensusMain.run(BASE, provider(List.of("<3>Nov 24 10:00:00 2026 H-1 vpnd[1]: tunnel failed")),
                sink(out), sink(err), root);
        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"tool\":\"system_log_template_census\""));
    }

    @Test
    void reportIsWrittenAtomicallyUnderTheRoot(@TempDir Path root) throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = {"--from", "2026-11-01", "--to", "2026-11-30", "--sources", "cp_messages", "--out", root.resolve("c.json").toString()};
        int code = SystemLogTemplateCensusMain.run(args, provider(List.of("<3>Nov 24 10:00:00 2026 H-1 vpnd[1]: tunnel failed")),
                sink(new ByteArrayOutputStream()), sink(err), root);
        assertEquals(0, code);
        assertTrue(Files.readString(root.resolve("c.json")).contains("rare_and_severe"));
        assertFalse(Files.exists(root.resolve("c.json.part")));
    }

    @Test
    void anOutPathOutsideTheRootIsAUsageError(@TempDir Path root) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = {"--from", "2026-11-01", "--to", "2026-11-30", "--sources", "cp_messages", "--out", "/etc/census.json"};
        assertEquals(2, SystemLogTemplateCensusMain.run(args, provider(List.of()), sink(new ByteArrayOutputStream()), sink(err), root));
        assertThrows(IllegalArgumentException.class, () -> SystemLogTemplateCensusMain.outPath(root.resolve("sub/x.json").toString(), root));
        assertThrows(IllegalArgumentException.class, () -> SystemLogTemplateCensusMain.outPath(root + "/../x.json", root));
    }

    @Test
    void badArgumentsExitTwoAndAFailedRunExitsThreeWithoutAValue(@TempDir Path root) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(2, SystemLogTemplateCensusMain.run(new String[] {"--nope", "1"}, provider(List.of()), sink(new ByteArrayOutputStream()), sink(err), root));
        SystemLogCensusFeed.Provider failing = (leak, progress) -> {
            throw new IllegalStateException("secret detail 192.0.2.5");
        };
        ByteArrayOutputStream err2 = new ByteArrayOutputStream();
        assertEquals(3, SystemLogTemplateCensusMain.run(BASE, failing, sink(new ByteArrayOutputStream()), sink(err2), root));
        assertFalse(err2.toString(StandardCharsets.UTF_8).contains("192.0.2.5"));
        assertTrue(err2.toString(StandardCharsets.UTF_8).contains("fatal illegalstateexception"));
    }
}
