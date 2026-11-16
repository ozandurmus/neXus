package com.securityexpert.nexus.ui2.platform;

/**
 * Closed C3C discriminator; never inferred from a reference. {@code SAML_GROUP} (V241) is a group binding of the SAML
 * (ADFS) profile: it matches like {@code DIRECTORY_GROUP} but only inside its own profile id, so an LDAP group and a SAML
 * group of the same spelling never meet.
 */
public enum DirectoryBindingKind {
    LEGACY, DIRECTORY_GROUP, DIRECTORY_PRINCIPAL, SAML_GROUP;

    /** True for a binding that matches a group value the signed-in actor carries (LDAP group or SAML group). */
    public boolean isGroupKind() {
        return this == DIRECTORY_GROUP || this == SAML_GROUP;
    }
}
