package com.securityexpert.nexus.ui2.service.sanitizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A sanitizer work file is named by a generated UUID and never leaves the work directory. */
class SanitizerWorkFileTest {
    private static final Path WORK = Path.of("/sanitizer-work");

    @Test
    void aGeneratedIdResolvesInsideTheWorkDirectory() {
        String id = UUID.randomUUID().toString();
        assertEquals(WORK.resolve(id + ".out"), SanitizerService.workFile(WORK, id, ".out"));
    }

    @Test
    void anythingElseIsRefused() {
        for (String bad : new String[] {"../../etc/passwd", "..", "a/b", UUID.randomUUID() + "/../x", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> SanitizerService.workFile(WORK, bad, ".in"), String.valueOf(bad));
        }
    }
}
