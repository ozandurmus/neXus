package com.securityexpert.nexus.ui2.capability;

/**
 * {@code capability_registry.transport.kind} (C4 §2.2, §5). Only
 * {@link #SSH_EXEC} has a shipped adapter in this movement ({@code worker}
 * §2 module-placement table); {@link #SSH_INTERACTIVE} and
 * {@link #PAN_XML_API} are schema-defined and Java-interface-declared, not
 * yet implemented (C4 §2.3 closing paragraph) -- a capability declaring
 * either compiles and stays {@code CAP-SPEC}/{@code CAP-OFFLINE} but never
 * reaches {@code EXECUTING} (contract §1).
 */
public enum TransportKind {
    SSH_EXEC,
    SSH_INTERACTIVE,
    PAN_XML_API;

    public static TransportKind fromSpecValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("transport.kind must not be null");
        }
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "ssh_exec" -> SSH_EXEC;
            case "ssh_interactive" -> SSH_INTERACTIVE;
            case "xml_api", "pan_xml_api" -> PAN_XML_API;
            default -> throw new IllegalArgumentException("unrecognized transport.kind: " + value);
        };
    }
}
