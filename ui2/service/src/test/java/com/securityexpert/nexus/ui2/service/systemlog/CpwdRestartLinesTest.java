package com.securityexpert.nexus.ui2.service.systemlog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Restart counting over synthetic cpwd.elg lines in the measured header shape ({@code [<proc> <pid> <tid>]@<host>[<DD>
 * <Mon> <HH:MM:SS>] <text>}; findings section 3.3). The text vocabulary is the PROVISIONAL rule's own, not a measured
 * sample; the host is a documentation name.
 */
class CpwdRestartLinesTest {
    private static String line(String day, String mon, String time, String text) {
        return "[cpwd 4242 140000]@gw.example.invalid[" + day + " " + mon + " " + time + "] " + text + "\n";
    }

    private static CpwdRestartLines.Summary count(String text) throws Exception {
        return CpwdRestartLines.count(new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void restartLinesAreCountedPerDeviceLocalHourAndTerminationsApart() throws Exception {
        String text = line("19", "Oct", "05:41:12", "Process FWD terminated abnormally")
                + line("19", "Oct", "05:41:14", "Restarting process FWD")
                + line("19", "Oct", "05:52:03", "Process FWD was restarted (count=2)")
                + line("19", "Oct", "05:58:40", "Process CPD terminated abnormally")
                + line("19", "Oct", "05:58:41", "Restarting process CPD")
                + line("19", "Oct", "07:00:00", "Process FWD started successfully")
                + line("20", "Oct", "05:10:00", "restart of process VPND requested\n")
                + "continuation fragment without a header that mentions a restart\n";

        var s = count(text);

        assertEquals(5, s.restartLines(), "four restart lines with a header plus one without");
        assertEquals(2, s.terminationLines());
        assertEquals(1, s.linesWithoutHeader());
        assertEquals(Map.of("Oct 19 05", 3, "Oct 20 05", 1), s.restartsPerHour(), "the header-less line has no hour");
        assertEquals(3, s.maxRestartsInOneHour());
    }

    @Test
    void wordsThatMerelyContainTheLettersAreNotRestarts() throws Exception {
        var s = count(line("1", "oct", "10:00:00", "cpwd started; prestart hooks done; autorestart disabled")
                + line("1", "oct", "10:00:01", "Process FWD started successfully (pid=100)"));
        assertEquals(0, s.restartLines(), "'prestart' and 'autorestart' are not the word restart");
        assertEquals(0, s.terminationLines());
        assertEquals(2, s.lines());
    }

    @Test
    void summariesAddUpAcrossChunks() throws Exception {
        var a = count(line("19", "Oct", "05:41:14", "Restarting process FWD"));
        var b = count(line("19", "Oct", "05:45:00", "Restarting process FWD") + line("19", "Oct", "06:01:00", "restarted"));
        var sum = a.plus(b);
        assertEquals(3, sum.restartLines());
        assertEquals(Map.of("Oct 19 05", 2, "Oct 19 06", 1), sum.restartsPerHour());
        assertEquals(CpwdRestartLines.Summary.EMPTY, CpwdRestartLines.Summary.EMPTY.plus(CpwdRestartLines.Summary.EMPTY));
    }
}
