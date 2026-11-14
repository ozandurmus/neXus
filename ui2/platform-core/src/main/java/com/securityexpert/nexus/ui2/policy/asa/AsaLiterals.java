package com.securityexpert.nexus.ui2.policy.asa;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Official Cisco ASA 9.22 protocol (Table 6) and port (Table 7) literal tables (A0-ASA-15).
 * Loaded once from classpath resources without logging any configuration tokens.
 */
final class AsaLiterals {
    record PortLiteral(String literal, Set<String> protocols, int port) {}

    private static final String PORT_RESOURCE = "/policy/asa/cisco_port_literals.tsv";
    private static final String PROTOCOL_RESOURCE = "/policy/asa/cisco_protocol_literals.tsv";

    static final Map<String, PortLiteral> PORTS = loadPorts();
    static final Map<String, Integer> PROTOCOLS = loadProtocols();

    private AsaLiterals() {}

    /**
     * Resolves a port token (numeric 0..65535 or Table 7 literal valid for {@code protocol}, A0-ASA-15).
     * Returns null when the token is outside Table 7 or not valid for the given protocol (e.g. UDP-only on TCP).
     */
    static Integer resolvePort(String token, String protocol) {
        if (token == null || token.isEmpty() || protocol == null) return null;
        if (isUnsignedDigits(token) && token.length() <= 5) {
            int p = Integer.parseInt(token);
            return p >= 0 && p <= 65535 ? p : null;
        }
        // A0-ASA-15: respect Table 7's TCP/UDP column; a literal valid only for UDP on a TCP ACE is unresolved.
        PortLiteral entry = PORTS.get(token);
        if (entry == null || !entry.protocols().contains(protocol)) return null;
        return entry.port();
    }

    static boolean isKnownProtocolLiteral(String token) {
        return token != null && PROTOCOLS.containsKey(token);
    }

    static boolean isUnsignedDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static Map<String, PortLiteral> loadPorts() {
        Map<String, PortLiteral> map = new LinkedHashMap<>();
        try (InputStream in = AsaLiterals.class.getResourceAsStream(PORT_RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing ASA port literals resource");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int t1 = line.indexOf('\t');
                    int t2 = t1 < 0 ? -1 : line.indexOf('\t', t1 + 1);
                    if (t1 <= 0 || t2 <= t1 + 1) continue;
                    String literal = line.substring(0, t1).trim();
                    String protosRaw = line.substring(t1 + 1, t2).trim();
                    int port = Integer.parseInt(line.substring(t2 + 1).trim());
                    Set<String> protos = new LinkedHashSet<>();
                    for (String p : protosRaw.split(",", -1)) {
                        String trimmed = p.trim();
                        if (!trimmed.isEmpty()) protos.add(trimmed);
                    }
                    map.put(literal, new PortLiteral(literal, Collections.unmodifiableSet(protos), port));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read ASA port literals resource", e);
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Integer> loadProtocols() {
        Map<String, Integer> map = new LinkedHashMap<>();
        try (InputStream in = AsaLiterals.class.getResourceAsStream(PROTOCOL_RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing ASA protocol literals resource");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int t1 = line.indexOf('\t');
                    if (t1 <= 0) continue;
                    String literal = line.substring(0, t1).trim();
                    int number = Integer.parseInt(line.substring(t1 + 1).trim());
                    map.put(literal, number);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read ASA protocol literals resource", e);
        }
        return Collections.unmodifiableMap(map);
    }
}
