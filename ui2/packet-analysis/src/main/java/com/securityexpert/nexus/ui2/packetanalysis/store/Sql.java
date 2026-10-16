package com.securityexpert.nexus.ui2.packetanalysis.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** SQL text kept as resource files under {@code packetanalysis/}, ported verbatim from the reference engine. */
public final class Sql {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private Sql() {
    }

    /** The statement at {@code packetanalysis/<name>.sql}, e.g. {@code store/01_create_settings}. */
    public static String load(String name) {
        return CACHE.computeIfAbsent(name, n -> {
            String resource = "/packetanalysis/" + n + ".sql";
            try (InputStream in = Sql.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("missing SQL resource " + resource);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
