package com.securityexpert.nexus.ui2.worker.systemlog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Synthetic data only: no real device names, addresses or log lines. */
final class Fixtures {
    static final long NOW = 1_760_700_000L;
    static final long CURSOR_EPOCH = NOW - 21_600L;
    static final long MIB = 1024L * 1024L;
    static final String ANCHOR = "anchor-hmac-synthetic";
    static final PlannerConfig CFG = PlannerConfig.defaults();
    static final String MESSAGES = "/var/log/messages";
    static final String FWD_DIR = "/opt/synth/fw/log/";

    private Fixtures() {
    }

    /** A file of the messages family: {@code name} is the part after {@code /var/log/}. */
    static FileStat msg(String name, long inode, long size) {
        return new FileStat("/var/log/" + name, inode, size, NOW - 100);
    }

    static FileStat file(String path, long inode, long size) {
        return new FileStat(path, inode, size, NOW - 100);
    }

    static String render(long epoch, List<FileStat> files) {
        StringBuilder out = new StringBuilder().append(epoch).append('\n');
        for (FileStat f : files) {
            out.append(f.path()).append(' ').append(f.inode()).append(' ').append(f.size()).append(' ')
                    .append(f.mtime()).append('\n');
        }
        return out.toString();
    }

    static StatParseResult stat(FileStat... files) {
        return StatParser.parse(render(NOW, List.of(files)));
    }

    static SourceCursor cursor(long inode, long offset, FileStat... snapshot) {
        return new SourceCursor(inode, offset, offset == 0 ? null : ANCHOR, List.of(snapshot), CURSOR_EPOCH);
    }

    static SourcePlan messages(SourceCursor cursor, FileStat... files) {
        return ReadPlanner.plan(SourceFamily.CP_MESSAGES, cursor, stat(files), CFG);
    }

    static SourcePlan messages(SourceCursor cursor, PlanContext context, FileStat... files) {
        return ReadPlanner.plan(SourceFamily.CP_MESSAGES, cursor, stat(files), CFG, context);
    }

    static List<String> paths(SourcePlan plan) {
        List<String> out = new ArrayList<>();
        for (ReadAction r : plan.reads()) {
            out.add(r.path());
        }
        return out;
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
