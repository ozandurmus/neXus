package com.securityexpert.nexus.ui2.worker.transport.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

/**
 * One persistent, PTY-backed interactive shell channel, ported from the
 * pre-Java product's own real-fleet-proven {@code InteractiveSshSession}
 * (its Check Point configuration collector). Some
 * Check Point Gaia Embedded / Quantum Spark appliances accept an
 * interactive shell login but reject a bare {@code exec} channel request
 * outright (measured live by the Product Owner, 2026-09-21: every literal
 * form of a plain {@code exec} identity read ran out its full timeout on
 * such a device, PTY or not) -- this mirrors a normal operator SSH session
 * instead: open one shell, learn its prompt, then send each command and
 * read until that prompt reappears.
 *
 * <p>Deliberately narrower than the Python original: no marker/exit-status
 * framing mode, since the confirm's identity/HA reads never used it there
 * either (only the prompt-synchronized path). One command at a time, never
 * concurrent, matching the one-session-per-confirm rule.</p>
 */
final class InteractiveShellSession implements AutoCloseable {

    private static final Pattern ANSI_ESCAPE = Pattern.compile("(?:[@-Z\\\\-_]|\\[[0-?]*[ -/]*[@-~])");
    private static final List<String> CLI_ERROR_PATTERNS = List.of(
            "command not found", "unknown command", "invalid command", "syntax error",
            // FortiOS
            "command fail. return code", "command parse error", "unknown action",
            "not a valid command", "permission denied", "not authorized", "authorization failed");

    private final ChannelShell channel;
    private final InputStream in;
    private final OutputStream out;
    private String prompt;

    InteractiveShellSession(Session jschSession, int connectTimeoutMs) throws JSchException, IOException {
        this.channel = (ChannelShell) jschSession.openChannel("shell");
        this.channel.setPtyType("vt100");
        this.channel.setPtySize(4096, 10000, 0, 0);
        this.in = channel.getInputStream();
        this.out = channel.getOutputStream();
        this.channel.connect(connectTimeoutMs);
        // Drain the login banner, then ask the shell to repaint its prompt (never a
        // device command) and learn the prompt string from that repaint alone.
        readUntilQuiet(5000, 350);
        try {
            out.write('\n');
            out.flush();
        } catch (IOException ignored) {
            // handled by the caller's next read/write failing
        }
        String painted = readUntilQuiet(5000, 350);
        this.prompt = promptCandidateOf(painted);
    }

    boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    public void close() {
        channel.disconnect();
    }

    /** How one command on the shell ended: only {@link Kind#TIMED_OUT} means the prompt never came back. */
    record Result(Kind kind, String text) {
        enum Kind { OUTPUT, EMPTY, CLI_ERROR, TIMED_OUT, NOT_SENT }
    }

    /**
     * Runs one command on this persistent shell. Distinguishes a command that answered with its prompt and no text
     * (normal for a PAN-OS {@code set cli ...} setting) from one whose prompt never came back -- before 2026-09-23 both
     * were {@code null} and reported as a timeout, which failed every Palo Alto set-format config read.
     */
    Result runForResult(String command, int timeoutMs) {
        String normalized = command == null ? "" : command.strip();
        if (normalized.isEmpty() || normalized.contains("\n") || normalized.contains("\r")) {
            return new Result(Result.Kind.NOT_SENT, null);
        }
        drainReady();
        try {
            out.write((normalized + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            return new Result(Result.Kind.NOT_SENT, null);
        }

        StringBuilder raw = new StringBuilder();
        long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
        long lastData = System.currentTimeMillis();
        boolean sawData = false;
        boolean completed = false;
        byte[] buf = new byte[4096];
        while (System.currentTimeMillis() < deadline) {
            boolean got = false;
            try {
                while (in.available() > 0) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    raw.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    got = true;
                    sawData = true;
                }
            } catch (IOException ignored) {
                // treated as no data this iteration; the deadline still governs
            }
            if (got) {
                lastData = System.currentTimeMillis();
                String current = stripTerminalControl(raw.toString());
                // FortiOS pages at the terminal height with " --More-- " and waits: answer with a space, drop the
                // marker, keep reading (no console setting is changed on the device).
                if (current.stripTrailing().endsWith(MORE)) {
                    int at = raw.lastIndexOf(MORE);
                    if (at >= 0) {
                        raw.delete(at, raw.length());
                    }
                    try {
                        out.write(' ');
                        out.flush();
                    } catch (IOException e) {
                        return new Result(Result.Kind.NOT_SENT, null);
                    }
                    continue;
                }
                if (prompt != null && (current.stripTrailing().endsWith(prompt) || isPromptVariant(lastLine(current)))) {
                    completed = true;
                    break;
                }
            } else if (sawData && prompt == null && System.currentTimeMillis() - lastData >= 1250) {
                // Fallback only when a stable prompt could not be learned at all.
                completed = true;
                break;
            }
            sleepQuietly(40);
        }
        if (!completed) {
            return new Result(Result.Kind.TIMED_OUT, null);
        }

        String text = stripTerminalControl(raw.toString());
        String observedPrompt = promptCandidateOf(text);
        if (observedPrompt != null) {
            prompt = observedPrompt;
        }
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(0).strip().equals(normalized)) {
            lines.remove(0);
        }
        if (prompt != null && !lines.isEmpty() && (lines.get(lines.size() - 1).strip().equals(prompt)
                || isPromptVariant(lines.get(lines.size() - 1).strip()))) {
            lines.remove(lines.size() - 1);
        }
        String stdout = String.join("\n", lines).strip();
        if (stdout.isEmpty()) {
            return new Result(Result.Kind.EMPTY, "");
        }
        if (looksLikeCliError(stdout)) {
            return new Result(Result.Kind.CLI_ERROR, stdout);
        }
        return new Result(Result.Kind.OUTPUT, stdout);
    }

    /**
     * {@link #runForResult} for a command that asks questions before its prompt returns: when the output so far ends
     * with an answer's {@code promptSuffix} (case-insensitive), its reply is sent once. The reply is never echoed into
     * the returned text by this method (a device that echoes it is a device problem; the Cyber Controller does not).
     */
    Result runAnswering(String command, List<com.securityexpert.nexus.ui2.jobs.transport.PromptAnswer> answers, int timeoutMs) {
        String normalized = command == null ? "" : command.strip();
        if (normalized.isEmpty() || normalized.contains("\n") || normalized.contains("\r")) {
            return new Result(Result.Kind.NOT_SENT, null);
        }
        drainReady();
        try {
            out.write((normalized + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            return new Result(Result.Kind.NOT_SENT, null);
        }
        boolean[] answered = new boolean[answers.size()];
        StringBuilder raw = new StringBuilder();
        long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
        byte[] buf = new byte[4096];
        boolean completed = false;
        while (System.currentTimeMillis() < deadline) {
            boolean got = false;
            try {
                while (in.available() > 0) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    raw.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    got = true;
                }
            } catch (IOException ignored) {
                // the deadline still governs
            }
            if (got) {
                String current = stripTerminalControl(raw.toString()).stripTrailing();
                if (prompt != null && current.endsWith(prompt)) {
                    completed = true;
                    break;
                }
                String lower = current.toLowerCase(Locale.ROOT);
                for (int i = 0; i < answers.size(); i++) {
                    if (!answered[i] && lower.endsWith(answers.get(i).promptSuffix().toLowerCase(Locale.ROOT))) {
                        try {
                            out.write((new String(answers.get(i).reply()) + "\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        } catch (IOException e) {
                            return new Result(Result.Kind.NOT_SENT, null);
                        }
                        answered[i] = true;
                        raw.append('\n');
                        break;
                    }
                }
            }
            sleepQuietly(40);
        }
        if (!completed) {
            return new Result(Result.Kind.TIMED_OUT, null);
        }
        String text = stripTerminalControl(raw.toString());
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(0).strip().equals(normalized)) {
            lines.remove(0);
        }
        if (prompt != null && !lines.isEmpty() && lines.get(lines.size() - 1).strip().equals(prompt)) {
            lines.remove(lines.size() - 1);
        }
        String stdout = String.join("\n", lines).strip();
        if (stdout.isEmpty()) {
            return new Result(Result.Kind.EMPTY, "");
        }
        return new Result(looksLikeCliError(stdout) ? Result.Kind.CLI_ERROR : Result.Kind.OUTPUT, stdout);
    }

    /** The command's output, or {@code null} for any non-output ending (the "try the next form" contract). */
    String run(String command, int timeoutMs) {
        Result r = runForResult(command, timeoutMs);
        return r.kind() == Result.Kind.OUTPUT ? r.text() : null;
    }

    private void drainReady() {
        try {
            byte[] buf = new byte[4096];
            while (in.available() > 0) {
                if (in.read(buf) < 0) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // nothing to drain, or the channel is already gone -- the next write/read reports that
        }
    }

    private String readUntilQuiet(int timeoutMs, int quietMs) {
        StringBuilder chunks = new StringBuilder();
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
        long lastData = System.currentTimeMillis();
        boolean sawData = false;
        byte[] buf = new byte[4096];
        while (System.currentTimeMillis() < deadline) {
            boolean got = false;
            try {
                while (in.available() > 0) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    chunks.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    got = true;
                    sawData = true;
                }
            } catch (IOException ignored) {
                break;
            }
            if (got) {
                lastData = System.currentTimeMillis();
            } else if (sawData && System.currentTimeMillis() - lastData >= quietMs) {
                break;
            }
            sleepQuietly(40);
        }
        return chunks.toString();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String stripTerminalControl(String value) {
        String text = ANSI_ESCAPE.matcher(value == null ? "" : value).replaceAll("");
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /** The last plausible prompt line, for read framing only -- never used to decide Clish vs
     * Expert; command success is the only capability evidence, matching the Python original. */
    private static String promptCandidateOf(String value) {
        String[] lines = stripTerminalControl(value).split("\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.length() > 240) {
                continue;
            }
            char last = line.charAt(line.length() - 1);
            if (last == '>' || last == '#' || last == '$') {
                return line;
            }
        }
        return null;
    }

    private static final String MORE = "--More--";

    private static String lastLine(String text) {
        String t = text.stripTrailing();
        int nl = t.lastIndexOf('\n');
        return (nl < 0 ? t : t.substring(nl + 1)).strip();
    }

    /**
     * The learned prompt with a mode in brackets ("FGT # " -> "FGT (global) # ", "FGT (root) # "): FortiOS shows the
     * context it is in; the host part must be the learned one, so device output is not mistaken for a prompt.
     */
    boolean isPromptVariant(String line) {
        return isPromptVariant(prompt, line);
    }

    static boolean isPromptVariant(String prompt, String line) {
        if (prompt == null || line == null || line.isEmpty()) {
            return false;
        }
        String stem = prompt.replaceAll("\\s*[#>$]\\s*$", "").replaceAll("\\s*\\([^)]*\\)$", "").strip();
        if (stem.length() < 2) {
            return false;
        }
        return line.matches(java.util.regex.Pattern.quote(stem) + "(\\s*\\([^)]*\\))?\\s*[#>$]");
    }

    private static boolean looksLikeCliError(String stdout) {
        String lower = stdout.toLowerCase(Locale.ROOT);
        for (String pattern : CLI_ERROR_PATTERNS) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
