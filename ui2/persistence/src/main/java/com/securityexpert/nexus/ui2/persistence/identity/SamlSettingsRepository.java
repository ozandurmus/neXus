package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.Optional;

/** {@code saml_settings} persistence port (V241): one row or none (none = SAML off). */
public interface SamlSettingsRepository {

    Optional<SamlSettingsRecord> find();

    /** Inserts or replaces the single row; {@code updatedBy} on the record is the audited actor. */
    void save(SamlSettingsRecord record);
}
