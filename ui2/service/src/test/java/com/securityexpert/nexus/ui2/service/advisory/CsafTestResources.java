package com.securityexpert.nexus.ui2.service.advisory;

import java.io.IOException;
import java.io.InputStream;

/** Test resources of the advisory parser tests ({@code src/test/resources/advisory}). */
final class CsafTestResources {

    private CsafTestResources() {
    }

    static byte[] bytes(String name) {
        try (InputStream in = CsafTestResources.class.getResourceAsStream("/advisory/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
