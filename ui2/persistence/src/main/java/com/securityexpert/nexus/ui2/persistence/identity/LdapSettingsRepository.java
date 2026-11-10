package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.Optional;

/** {@code ldap_settings} persistence port (V205): one row or none. */
public interface LdapSettingsRepository {

    Optional<LdapSettingsRecord> find();

    /** Inserts or replaces the single row; {@code updatedBy} on the record is the audited actor. */
    void save(LdapSettingsRecord record);
}
