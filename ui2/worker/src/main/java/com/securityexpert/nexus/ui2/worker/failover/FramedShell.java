package com.securityexpert.nexus.ui2.worker.failover;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * (docs/design/CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md §3.2):
 * {@code echo __NX_<nonce>_BEGIN__; <command>; echo __NX_<nonce>_END_$?__}. The nonce is 128 bits from
 * {@link SecureRandom}, new per command, never derived from device data. Output is the text strictly between the BEGIN
 * line and the END marker, cleaned of terminal escape sequences and CR; the exit status is the END marker's digits.
 *
 * <p>Any framing failure (deadline, closed channel, output or preamble over its bound, END before BEGIN, a second
 * BEGIN, an invalid status) closes the channel and marks this shell broken: it is never reused, and every later call
 * fails without sending anything. Nothing from the device is logged.</p>
 */
final class FramedShell implements AutoCloseable {
    static final int MAX_PREAMBLE_BYTES = 64 * 1024;
    private static final int LINE_SLACK_BYTES = 4096;
    private static final Duration READ_SLICE = Duration.ofMillis(50);
    private static final Pattern TERMINAL = Pattern.compile(
            "\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|[()*+].|.)");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B-\\x1F\\x7F]");

    private final ShellChannel channel;
    private final SecureRandom random;
    private final LongSupplier nanoTime;
    private boolean broken, closed;
    private int sent;

    FramedShell(ShellChannel channel, SecureRandom random, LongSupplier nanoTime) {
        this.channel = java.util.Objects.requireNonNull(channel);
        this.random = java.util.Objects.requireNonNull(random);
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime);
    }

    boolean broken() { return broken; }

    /** Commands sent on this shell so far. */
    int sent() { return sent; }

    /** The exact framed line for {@code command} and {@code nonce} (the gate key's shape with both filled in). */
    static String framedLine(String nonce, String command) {
        return "echo " + begin(nonce) + "; " + command + "; echo " + endPrefix(nonce) + "$?__";
    }

    private static String begin(String nonce) { return "__NX_" + nonce + "_BEGIN__"; }

    private static String endPrefix(String nonce) { return "__NX_" + nonce + "_END_"; }

    ExecResult run(String command, Duration timeout, int maxOutputChars) {
        if (broken) return new ExecResult.ChannelFailed("SHELL_CLOSED");
        if (command == null || command.isBlank() || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0)
            return new ExecResult.ChannelFailed("COMMAND_NOT_SENDABLE");
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String nonce = HexFormat.of().formatHex(bytes);
        String begin = begin(nonce);
        Pattern end = Pattern.compile("(.*)" + Pattern.quote(endPrefix(nonce)) + "([0-9]{1,3})__[ \t]*");
        long deadline = nanoTime.getAsLong() + timeout.toNanos();
        try {
            channel.sendLine(framedLine(nonce, command));
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
            if (remaining <= 0) return fail(new ExecResult.TimedOut());
            int read;
            try {
                read = channel.read(buffer, Duration.ofNanos(Math.min(remaining, READ_SLICE.toNanos())));
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
                    if (line.strip().equals(begin)) body = true;
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

    /** Terminal escape sequences, then every CR and other control character except TAB. */
    static String clean(String line) {
        return CONTROL.matcher(TERMINAL.matcher(line).replaceAll("")).replaceAll("");
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
