package com.securityexpert.nexus.ui2.worker.inventory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Reads a {@code fixtures/inventory/...} classpath resource as text, for the parser fixture tests. */
public final class Fixtures {

    private Fixtures() {
    }

    public static String read(String relativePath) {
        String resourcePath = "fixtures/inventory/" + relativePath;
        try (InputStream stream = Fixtures.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("missing test fixture on classpath: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
