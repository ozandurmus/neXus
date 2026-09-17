package com.securityexpert.nexus.ui2.persistence.identity;

public record DirectoryProfileRecord(
    java.util.UUID id,
    String profileName,
    String host,
    int port,
    String transport,
    String trustFormat,
    String trustMaterialPem,
    String storePinEncrypted,
    String bindDnTemplate,
    String groupSearchBaseDn,
    String accessGroupReference,
    boolean isActive
) {}
