package com.securityexpert.nexus.ui2.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class SecretFileTest {

    @TempDir
    Path tempDir;

    @Test
    void readsTrimmedContent() throws IOException {
        Path file = tempDir.resolve("secret");
        Files.writeString(file, "s3cr3t\n");

        assertEquals("s3cr3t", SecretFile.readRequired(file, "test.purpose"));
    }

    @Test
    void missingFileFailsClosed() {
        Path missing = tempDir.resolve("does-not-exist");

        SecretFile.SecretFileException ex = assertThrows(
                SecretFile.SecretFileException.class,
                () -> SecretFile.readRequired(missing, "test.purpose"));

        assertTrue(ex.getMessage().contains("test.purpose"));
        assertFalse(ex.getMessage().contains(missing.toString()));
    }

    @Test
    void emptyFileFailsClosed() throws IOException {
        Path file = tempDir.resolve("empty");
        Files.writeString(file, "");

        assertThrows(SecretFile.SecretFileException.class,
                () -> SecretFile.readRequired(file, "test.purpose"));
    }
}
