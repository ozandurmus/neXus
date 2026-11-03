package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;

/**
 * Makes a fixture project independent of a tshark installation. The application index resolves the tshark binary
 * before it looks at any capture and fails the whole analysis when none is installed (a CI runner, any machine without
 * Wireshark). The frozen fixtures name no capture file, so tshark is never started; this points
 * {@code preferences.tshark} at a stub file that only has to exist. Never use it where a test decodes a real capture.
 */
public final class TsharkStub {
    private TsharkStub() {
    }

    /** Writes the stub into {@code dir} and stores it as the project's {@code preferences.tshark}. */
    public static void install(Path project, Path dir) throws Exception {
        Path stub = dir.resolve("tshark-stub");
        if (!Files.exists(stub)) {
            Files.writeString(stub, "#!/bin/sh\necho 'tshark stub: not started by fixture tests' >&2\nexit 127\n");
            stub.toFile().setExecutable(true);
        }
        String prefs = new ObjectMapper().writeValueAsString(Map.of("tshark", stub.toRealPath().toString()));
        try (Connection c = Engine.open(project, 1)) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM settings WHERE key='preferences'")) {
                ps.execute();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO settings VALUES ('preferences', ?)")) {
                ps.setString(1, prefs);
                ps.execute();
            }
        }
    }
}
