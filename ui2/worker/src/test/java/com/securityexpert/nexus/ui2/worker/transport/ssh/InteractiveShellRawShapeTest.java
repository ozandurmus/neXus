package com.securityexpert.nexus.ui2.worker.transport.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InteractiveShellRawShapeTest {
    @Test
    void shapesOnlyWhatFollowsTheEchoAndNeverValues() {
        String cmd = "bash -lc 'cpinfo -y all'";
        String raw = "[Expert@SYNTH-01:0]# " + cmd + "\r\n\u001b[?25lHost SYNTH-01 at 192.0.2.7\b\b-\r";
        String shape = InteractiveShellSession.rawShape(new StringBuilder(raw), cmd, 12, 59_000);
        assertTrue(shape.contains("echoSeen=true"), shape);
        assertTrue(shape.contains("esc=1"), shape);
        assertTrue(shape.contains("cr=2"), shape);
        assertTrue(shape.contains("nl=1"), shape);
        assertTrue(shape.contains("bs=2"), shape);
        assertTrue(shape.contains("firstByteMs=12 idleMs=59000"), shape);
        assertFalse(shape.contains("SYNTH"), shape);
        assertFalse(shape.contains("192.0.2.7"), shape);
        assertEquals("<cr><nl><esc>[?9a a-9 a 9.9.9.9<bs><bs>-<cr>",
                InteractiveShellSession.controlShape("\r\n\u001b[?25lHost SYNTH-01 at 192.0.2.7\b\b-\r"));
    }

    @Test
    void noEchoShapesTheWholeAnswer() {
        String shape = InteractiveShellSession.rawShape(new StringBuilder("abc"), "cpinfo", -1, 100);
        assertTrue(shape.contains("echoSeen=false afterEcho=3"), shape);
        assertTrue(shape.contains("shape=\"a\""), shape);
    }
}
