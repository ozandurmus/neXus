package com.securityexpert.nexus.ui2.identity.ldap;

import java.nio.file.Path;

/** Server-owned configuration. No browser or environment PIN value. */
public record DirectoryProfile(String profileId, String host, int port, Transport transport,
        Path trustMaterialFile, Format format, Path storePinFile, String bindDnTemplate,
        String groupSearchBaseDn, String accessGroupReference) {
    public enum Transport { LDAPS, STARTTLS }
    public enum Format { PEM, JKS, PKCS12 }

    public DirectoryProfile {
        if (profileId == null || profileId.isBlank() || host == null || host.isBlank() || port < 1 || port > 65535
                || transport == null || format == null || trustMaterialFile == null
                || bindDnTemplate == null || !bindDnTemplate.contains("%s")
                || bindDnTemplate.replace("%s", "").contains("%")
                || bindDnTemplate.indexOf("%s") != bindDnTemplate.lastIndexOf("%s")
                || groupSearchBaseDn == null || groupSearchBaseDn.isBlank()
                || accessGroupReference == null || accessGroupReference.isBlank()) {
            throw new LdapStartupException("directory_profile_invalid");
        }
        if ((format == Format.PEM && storePinFile != null) || (format != Format.PEM && storePinFile == null)) {
            throw new LdapStartupException("trust_pin_configuration_invalid");
        }
    }

    @Override public String toString() { return "DirectoryProfile[redacted]"; }
}
