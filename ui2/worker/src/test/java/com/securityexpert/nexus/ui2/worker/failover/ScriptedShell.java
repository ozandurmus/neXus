package com.securityexpert.nexus.ui2.worker.failover;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.jobs.transport.ShellChannel;

/**
 * A scripted PTY login shell (no socket): prints a banner and a prompt, echoes every line with CR LF, and answers a
 * framed line through {@link Responder}. All text is synthetic. Reads with nothing queued advance {@link #clock} by the
 * requested wait, so deadlines are deterministic.
 */
class ScriptedShell implements ShellChannel {
    static final String PROMPT = "[Expert@FW-TEST-01:0]# ";
    private static final Pattern FRAMED = Pattern.compile(
            "echo (__NX_[0-9a-f]{32}_BEGIN__); (.*); echo (__NX_[0-9a-f]{32}_END_)\\$\\?__");

    /** Raw text the shell prints after the echo for {@code inner}; {@code null} = the command never ends. */
    @FunctionalInterface
    interface Responder {
        String respond(String inner, String begin, String endPrefix);
    }

    static String framed(String begin, String endPrefix, String output, int status) {
        return begin + "\r\n" + output.replace("\r\n", "\n").replace("\n", "\r\n") + endPrefix + status + "__\r\n" + PROMPT;
    }

    final AtomicLong clock;
    private final Responder responder;
    private final ArrayDeque<Byte> outbound = new ArrayDeque<>();
    final List<String> lines = new ArrayList<>();
    final List<String> inner = new ArrayList<>();
    int chunk = Integer.MAX_VALUE;
    boolean closed;
    int closes;

    ScriptedShell(AtomicLong clock, Responder responder) {
        this.clock = clock;
        this.responder = responder;
        emit("Synthetic login notice for tests only.\r\n\u001B]0;synthetic-title\u0007" + PROMPT);
    }

    void emit(String text) {
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) outbound.add(b);
    }

    @Override
    public void sendLine(String line) throws IOException {
        if (closed) throw new IOException("closed");
        lines.add(line);
        // PTY echo, with a bracketed-paste toggle as newer readline versions print it.
        emit(line + "\r\n\u001B[?2004l\r");
        Matcher framed = FRAMED.matcher(line);
        if (!framed.matches()) {
            emit("bash: synthetic syntax error\r\n" + PROMPT);
            return;
        }
        inner.add(framed.group(2));
        String reply = responder.respond(framed.group(2), framed.group(1), framed.group(3));
        if (reply != null) emit(reply);
    }

    @Override
    public int read(byte[] buffer, Duration wait) {
        if (closed) return -1;
        if (outbound.isEmpty()) {
            clock.addAndGet(wait.toNanos());
            return 0;
        }
        int n = 0;
        while (n < buffer.length && n < chunk && !outbound.isEmpty()) buffer[n++] = outbound.poll();
        return n;
    }

    @Override
    public void close() {
        closed = true;
        closes++;
    }
}
