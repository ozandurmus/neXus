package com.securityexpert.nexus.ui2.worker.failover;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ShellChannel;

/**
 * Runs one command at a time on one interactive shell channel, framed by random sentinels
 * (docs/design/CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md §3.2, amended by the review response §7):
 * {@code echo __NX_<nonce>_BEGIN__; ( <command> ) 2>/dev/null; echo __NX_<nonce>_END_$?__} -- or {@code 2>&1} for a read
 * whose exec form ran with a PTY (stderr was merged there). The subshell keeps every command's context (a VS
 * {@code vsenv}, the working directory, variables) to itself, as one exec channel did; {@code 2>/dev/null} drops stderr,
 * as the exec path returned stdout only. The nonce is 128 bits from {@link SecureRandom}, new per command, never derived
 * from device data.
 *
 * <p>Before the first command the login banner and first prompt are drained: the shell counts as ready after
 * {@link #READY_QUIET} of silence following its first output, or after {@link #READY_MAX} in any case (no device
 * command is sent for this). Every received line is rendered as a terminal would show it (CR returns to column 0 and
 * overwrites, backspace moves left, erase-line sequences erase; other escape sequences and control characters are
 * dropped). A BEGIN line may follow an un-newlined prompt on the same line.</p>
 *
 * <p>Any framing failure (deadline, closed channel, output or preamble over its bound, END before BEGIN, a second
 * BEGIN, an invalid status) sends Ctrl-C on a timeout, closes the channel and marks this shell broken: it is never
 * reused, and every later call fails without sending anything. Nothing from the device is logged.</p>
 */
final class FramedShell implements AutoCloseable {
    static final int MAX_PREAMBLE_BYTES = 64 * 1024;
    static final Duration READY_QUIET = Duration.ofMillis(350);
    static final Duration READY_MAX = Duration.ofSeconds(10);
    private static final int LINE_SLACK_BYTES = 4096;
    private static final Duration READ_SLICE = Duration.ofMillis(50);
    private static final Pattern ESCAPE = Pattern.compile(
            "\u001B(?:\\[([0-?]*)[ -/]*([@-~])|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|[()*+].|.)");

    private final ShellChannel channel;
    private final SecureRandom random;
    private final LongSupplier nanoTime;
    private boolean broken, closed, ready;
    private int sent;

    FramedShell(ShellChannel channel, SecureRandom random, LongSupplier nanoTime) {
        this.channel = java.util.Objects.requireNonNull(channel);
        this.random = java.util.Objects.requireNonNull(random);
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime);
    }

    boolean broken() { return broken; }

    /** Commands sent on this shell so far. */
    int sent() { return sent; }

    /** The exact framed line (the gate key's shape with nonce, command and stderr rule filled in). */
    static String framedLine(String nonce, String command, boolean mergeStderr) {
        return "echo " + begin(nonce) + "; ( " + command + " ) " + (mergeStderr ? "2>&1" : "2>/dev/null")
                + "; echo " + endPrefix(nonce) + "$?__";
    }

    private static String begin(String nonce) { return "__NX_" + nonce + "_BEGIN__"; }

    private static String endPrefix(String nonce) { return "__NX_" + nonce + "_END_"; }

    ExecResult run(String command, Duration timeout, int maxOutputChars) {
        return run(command, false, timeout, maxOutputChars);
    }

    ExecResult run(String command, boolean mergeStderr, Duration timeout, int maxOutputChars) {
        if (broken) return new ExecResult.ChannelFailed("SHELL_CLOSED");
        if (command == null || command.isBlank() || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0)
            return new ExecResult.ChannelFailed("COMMAND_NOT_SENDABLE");
        if (!ready) {
            ExecResult notReady = awaitReady();
            if (notReady != null) return notReady;
        }
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String nonce = HexFormat.of().formatHex(bytes);
        String begin = begin(nonce);
        Pattern end = Pattern.compile("(.*)" + Pattern.quote(endPrefix(nonce)) + "([0-9]{1,3})__[ \t]*");
        long deadline = nanoTime.getAsLong() + timeout.toNanos();
        try {
            channel.sendLine(framedLine(nonce, command, mergeStderr));
            sent++;
        } catch (IOException failed) {
            return fail(new ExecResult.ChannelFailed("SHELL_SEND_FAILED"));
        } catch (RuntimeException refused) {
            // Admission or cancellation refused the line: nothing may follow on this shell.
            close();
            throw refused;
        }
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        StringBuilder output = new StringBuilder();
        boolean body = false;
        long preamble = 0;
        byte[] buffer = new byte[8192];
        while (true) {
            long remaining = deadline - nanoTime.getAsLong();
            if (remaining <= 0) {
                interruptQuietly();
                return fail(new ExecResult.TimedOut());
            }
            int read;
            try {
                read = channel.read(buffer, Duration.ofNanos(Math.min(remaining, READ_SLICE.toNanos())));
            } catch (InterruptedIOException interrupted) {
                Thread.currentThread().interrupt();
                return fail(new ExecResult.ChannelFailed("INTERRUPTED"));
            } catch (IOException failed) {
                return fail(new ExecResult.ChannelFailed("SHELL_READ_FAILED"));
            }
            if (read < 0) return fail(new ExecResult.ChannelFailed("SHELL_CLOSED"));
            for (int i = 0; i < read; i++) {
                byte b = buffer[i];
                if (!body && ++preamble > MAX_PREAMBLE_BYTES)
                    return fail(new ExecResult.ChannelFailed("SHELL_PREAMBLE_LIMIT_EXCEEDED"));
                if (b != '\n') {
                    pending.write(b);
                    if (pending.size() > maxOutputChars + LINE_SLACK_BYTES)
                        return fail(new ExecResult.ChannelFailed("OUTPUT_LIMIT_EXCEEDED"));
                    continue;
                }
                String line = clean(pending.toString(StandardCharsets.UTF_8));
                pending.reset();
                Matcher marker = end.matcher(line);
                if (!body) {
                    if (isBegin(line, begin)) body = true;
                    else if (marker.matches()) return fail(new ExecResult.ChannelFailed("SHELL_FRAMING_DESYNC"));
                    continue;
                }
                if (marker.matches()) {
                    int status = Integer.parseInt(marker.group(2));
                    if (status > 255) return fail(new ExecResult.ChannelFailed("SHELL_FRAMING_DESYNC"));
                    output.append(marker.group(1));
                    if (output.length() > maxOutputChars)
                        return fail(new ExecResult.ChannelFailed("OUTPUT_LIMIT_EXCEEDED"));
                    // What follows END (the prompt) is discarded as the next command's preamble.
                    return new ExecResult.Completed(output.toString(), status);
                }
                if (line.contains(begin)) return fail(new ExecResult.ChannelFailed("SHELL_FRAMING_DESYNC"));
                output.append(line).append('\n');
                if (output.length() > maxOutputChars)
                    return fail(new ExecResult.ChannelFailed("OUTPUT_LIMIT_EXCEEDED"));
            }
        }
    }

    /**
     * The BEGIN line: exactly the marker, or the marker after an un-newlined prompt on the same line. The echoed input
     * never ends with the marker (it ends with {@code _END_$?__}); a cut-off echo ending in {@code echo <marker>} is
     * refused as well.
     */
    static boolean isBegin(String line, String begin) {
        String text = line.strip();
        return text.endsWith(begin) && !text.endsWith("echo " + begin);
    }

    /** Drains the banner and first prompt; ready after a quiet gap that follows output, or after READY_MAX. */
    private ExecResult awaitReady() {
        long start = nanoTime.getAsLong();
        long lastData = -1;
        long drained = 0;
        byte[] buffer = new byte[8192];
        while (true) {
            long now = nanoTime.getAsLong();
            if (now - start >= READY_MAX.toNanos()) break;
            if (lastData >= 0 && now - lastData >= READY_QUIET.toNanos()) break;
            int read;
            try {
                read = channel.read(buffer, READ_SLICE);
            } catch (InterruptedIOException interrupted) {
                Thread.currentThread().interrupt();
                return fail(new ExecResult.ChannelFailed("INTERRUPTED"));
            } catch (IOException failed) {
                return fail(new ExecResult.ChannelFailed("SHELL_READ_FAILED"));
            }
            if (read < 0) return fail(new ExecResult.ChannelFailed("SHELL_CLOSED"));
            if (read > 0) {
                lastData = nanoTime.getAsLong();
                drained += read;
                if (drained > MAX_PREAMBLE_BYTES)
                    return fail(new ExecResult.ChannelFailed("SHELL_PREAMBLE_LIMIT_EXCEEDED"));
            }
        }
        ready = true;
        return null;
    }

    /**
     * One received line as a terminal shows it: CR returns to column 0 and later text overwrites; backspace moves one
     * column left; {@code ESC [ K} / {@code ESC [ 0 K} erase to the end of the line, {@code ESC [ 2 K} erases the line;
     * every other escape sequence and control character except TAB is dropped.
     */
    static String clean(String raw) {
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        Matcher escape = ESCAPE.matcher(raw);
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\u001B') {
                if (escape.find(i) && escape.start() == i) {
                    if ("K".equals(escape.group(2))) {
                        String parameter = escape.group(1);
                        if (parameter.isEmpty() || "0".equals(parameter)) out.setLength(Math.min(cursor, out.length()));
                        else if ("2".equals(parameter)) { out.setLength(0); cursor = 0; }
                    }
                    i = escape.end();
                } else {
                    i++;
                }
                continue;
            }
            i++;
            if (c == '\r') { cursor = 0; continue; }
            if (c == '\b') { cursor = Math.max(0, cursor - 1); continue; }
            if (c != '\t' && (c < 0x20 || c == 0x7F)) continue;
            while (out.length() < cursor) out.append(' ');
            if (cursor < out.length()) out.setCharAt(cursor, c); else out.append(c);
            cursor++;
        }
        return out.toString();
    }

    private void interruptQuietly() {
        try {
            channel.interrupt();
        } catch (IOException | RuntimeException ignored) {
            // best effort before the close; closing the channel hangs up the shell in any case
        }
    }

    private ExecResult fail(ExecResult result) {
        close();
        return result;
    }

    @Override
    public void close() {
        broken = true;
        if (closed) return;
        closed = true;
        try {
            channel.close();
        } catch (RuntimeException ignored) {
            // closing is best effort; the session disconnect ends the channel in any case
        }
    }
}
