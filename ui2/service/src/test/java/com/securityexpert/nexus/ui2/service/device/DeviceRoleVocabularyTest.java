package com.securityexpert.nexus.ui2.service.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Fails when the Java role vocabulary and the latest database role check (or the frontend type) drift apart. */
class DeviceRoleVocabularyTest {

    private static final Pattern CHECK = Pattern.compile(
            "ADD CONSTRAINT chk_devices_role CHECK \\(role IN \\(([^)]*)\\)\\)");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static Set<String> quoted(String list) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("'([a-z_]+)'").matcher(list);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void javaVocabularyEqualsLatestMigrationCheck() throws IOException {
        Set<String> latest = null;
        int latestVersion = -1;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Matcher name = Pattern.compile("V(\\d+)__.*\\.sql").matcher(f.getFileName().toString());
                if (!name.matches()) {
                    continue;
                }
                Matcher c = CHECK.matcher(Files.readString(f));
                int v = Integer.parseInt(name.group(1));
                while (c.find()) {
                    if (v > latestVersion) {
                        latestVersion = v;
                        latest = quoted(c.group(1));
                    }
                }
            }
        }
        assertFalse(latest == null, "no chk_devices_role migration found");
        assertEquals(latest, new TreeSet<>(DeviceRegistrationService.DEVICE_ROLES));
    }

    @Test
    void frontendTypeEqualsJavaVocabulary() throws IOException {
        Path ts = Path.of("../frontend/src/auth/adminApi.ts");
        Matcher m = Pattern.compile("export type DeviceRole = ([^;]*);").matcher(Files.readString(ts));
        assertFalse(!m.find(), "DeviceRole type not found");
        Set<String> fe = new LinkedHashSet<>();
        Matcher q = Pattern.compile("\"([a-z_]+)\"").matcher(m.group(1));
        while (q.find()) {
            fe.add(q.group(1));
        }
        assertEquals(new TreeSet<>(DeviceRegistrationService.DEVICE_ROLES), new TreeSet<>(fe));
    }
}
