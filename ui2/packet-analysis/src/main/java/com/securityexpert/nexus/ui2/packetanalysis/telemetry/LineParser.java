package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

/** One format's line-by-line reader. Each call either uses, ignores or skips the line (counted on the result). */
interface LineParser {
    void line(String text);

    void finish();
}
