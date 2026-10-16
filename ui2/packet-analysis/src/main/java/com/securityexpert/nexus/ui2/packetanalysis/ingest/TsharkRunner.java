package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One tshark subprocess: stdout as UTF-8 text (malformed bytes replaced), stderr drained into a bounded buffer,
 * cancellation and an optional wall-clock timeout that terminate the process.
 *
 * <p>Nothing here logs. Packet content only flows through {@link #stdout()} to the caller; stderr (tshark's own
 * diagnostics) is kept to its first {@link #STDERR_LIMIT_BYTES} bytes so a noisy capture cannot grow memory.
 */
public final class TsharkRunner implements AutoCloseable {

    /** Upper bound of retained stderr; the ingest reads its first 8000 characters. */
    public static final int STDERR_LIMIT_BYTES = 64 * 1024;

    private static final String MAC_PATH = "/Applications/Wireshark.app/Contents/MacOS/tshark";

    private final Process process;
    private final Reader stdout;
    private final Thread stderrDrain;
    private final Thread watcher;
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final AtomicBoolean done = new AtomicBoolean();
    private final AtomicBoolean timedOut = new AtomicBoolean();

    private TsharkRunner(Process process, Cancellation cancel, Duration timeout) {
        this.process = process;
        this.stdout = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE));
        this.stderrDrain = new Thread(this::drainStderr, "tshark-stderr");
        this.stderrDrain.setDaemon(true);
        this.stderrDrain.start();
        long deadline = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        this.watcher = new Thread(() -> watch(cancel, deadline), "tshark-cancel");
        this.watcher.setDaemon(true);
        this.watcher.start();
    }

    /**
     * The reference {@code find_tshark}: the override if given, else {@code tshark} on PATH, the Windows default
     * install and the macOS app bundle; the first existing file wins, resolved to its real path.
     */
    public static String find(String override) {
        List<String> candidates = new ArrayList<>();
        if (override != null && !override.isEmpty()) {
            candidates.add(override);
        } else {
            candidates.add(which("tshark"));
            String programFiles = System.getenv().getOrDefault("ProgramFiles", "C:/Program Files");
            candidates.add(Path.of(programFiles, "Wireshark", "tshark.exe").toString());
            candidates.add(MAC_PATH);
        }
        for (String c : candidates) {
            if (c != null && !c.isEmpty() && Files.isRegularFile(Path.of(c))) {
                try {
                    return Path.of(c).toRealPath().toString();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalArgumentException(
                "tshark was not found. Install Wireshark, or set the full tshark path in Settings.");
    }

    private static String which(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path p = Path.of(dir, name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p.toString();
            }
        }
        return null;
    }

    /** Start tshark with stdin closed. */
    public static TsharkRunner start(List<String> command, Cancellation cancel, Duration timeout) {
        return start(command, cancel, timeout, List.of());
    }

    /** Start tshark with stdin closed and the named variables removed from its environment. */
    public static TsharkRunner start(List<String> command, Cancellation cancel, Duration timeout, List<String> unsetEnvironment) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.PIPE);
            unsetEnvironment.forEach(builder.environment()::remove);
            Process p = builder.start();
            p.getOutputStream().close();
            return new TsharkRunner(p, cancel == null ? Cancellation.NONE : cancel, timeout);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void drainStderr() {
        byte[] buf = new byte[8192];
        try (InputStream in = process.getErrorStream()) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                synchronized (stderr) {
                    int keep = Math.min(n, STDERR_LIMIT_BYTES - stderr.size());
                    if (keep > 0) {
                        stderr.write(buf, 0, keep);
                    }
                }
            }
        } catch (IOException e) {
            // The stream closes when the process is destroyed; what was read is kept.
        }
    }

    private void watch(Cancellation cancel, long deadline) {
        while (!done.get()) {
            if (cancel.isSet()) {
                process.destroy();
                return;
            }
            if (System.nanoTime() > deadline) {
                timedOut.set(true);
                process.destroy();
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** tshark's stdout as text. */
    public Reader stdout() {
        return stdout;
    }

    /** Wait for exit; the exit status (non-zero after a signal). */
    public int waitFor() {
        try {
            int code = process.waitFor();
            stderrDrain.join(TimeUnit.SECONDS.toMillis(5));
            return code;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestCancelledException("Ingest cancelled; committed frames can be resumed");
        }
    }

    /** Whether the timeout terminated the process. */
    public boolean timedOut() {
        return timedOut.get();
    }

    /** The first {@code chars} code points of stderr, decoded as UTF-8 with replacement. */
    public String stderrHead(int chars) {
        String text;
        synchronized (stderr) {
            text = stderr.toString(StandardCharsets.UTF_8);
        }
        if (text.codePointCount(0, text.length()) <= chars) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, chars));
    }

    /** Release stdout, terminate a still-running tshark (kill after 5 s) and stop the watcher. */
    @Override
    public void close() {
        done.set(true);
        try {
            stdout.close();
        } catch (IOException e) {
            // already closed
        }
        if (process.isAlive()) {
            process.destroy();
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
            watcher.join();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
