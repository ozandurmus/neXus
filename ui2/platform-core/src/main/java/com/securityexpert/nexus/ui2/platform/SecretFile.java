package com.securityexpert.nexus.ui2.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads one secret value from one file, failing closed. No caller may
 * substitute a default or fall back to an embedded literal when the file
 * is missing, unreadable, or empty (contract §7). Callers must never log
 * or embed the returned value in a URL that an exception message could
 * later surface.
 */
public final class SecretFile {

    private SecretFile() {
    }

    public static String readRequired(Path path, String purpose) {
        if (path == null) {
            throw new SecretFileException(purpose, "no path configured");
        }
        String content;
        try {
            content = Files.readString(path).strip();
        } catch (IOException e) {
            throw new SecretFileException(purpose, "unreadable");
        }
        if (content.isEmpty()) {
            throw new SecretFileException(purpose, "empty");
        }
        return content;
    }

    /**
     * Thrown on any secret-file failure. The message names only the
     * component/purpose and the failure category — never the file path
     * (which could embed a hostname) and never the value.
     */
    public static final class SecretFileException extends RuntimeException {
        public SecretFileException(String purpose, String reason) {
            super("secret file for " + purpose + " is " + reason);
        }
    }
}
