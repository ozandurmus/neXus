package com.securityexpert.nexus.ui2.service.security;

import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import com.securityexpert.nexus.ui2.platform.LdapConfigurationPort;

@Repository
public class LdapConfigurationRepository implements LdapConfigurationPort {

    private final DSLContext dsl;

    public LdapConfigurationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<LdapConfigurationView> getConfiguration() {
        return dsl.select(
                        DSL.field("server_url", String.class),
                        DSL.field("bind_dn", String.class),
                        DSL.field("base_dn", String.class),
                        DSL.field("search_filter", String.class),
                        DSL.field("ca_certificate", String.class))
                .from(DSL.table("ldap_configuration"))
                .limit(1)
                .fetchOptional()
                .map(record -> new LdapConfigurationView(
                        record.value1(),
                        record.value2(),
                        record.value3(),
                        record.value4(),
                        record.value5()
                ));
    }

    @Override
    public LdapConfigurationView updateConfiguration(String actorFingerprint, String serverUrl, String bindDn, char[] password, String baseDn, String searchFilter, String caCertificate) {
        String bindPassword = password != null ? new String(password) : "";
        dsl.insertInto(DSL.table("ldap_configuration"),
                        DSL.field("id"),
                        DSL.field("server_url"),
                        DSL.field("bind_dn"),
                        DSL.field("bind_password"),
                        DSL.field("base_dn"),
                        DSL.field("search_filter"),
                        DSL.field("ca_certificate"))
                .values("1", serverUrl, bindDn, bindPassword, baseDn, searchFilter, caCertificate)
                .onConflict(DSL.field("id"))
                .doUpdate()
                .set(DSL.field("server_url"), serverUrl)
                .set(DSL.field("bind_dn"), bindDn)
                .set(DSL.field("bind_password"), bindPassword)
                .set(DSL.field("base_dn"), baseDn)
                .set(DSL.field("search_filter"), searchFilter)
                .set(DSL.field("ca_certificate"), caCertificate)
                .execute();

        return new LdapConfigurationView(serverUrl, bindDn, baseDn, searchFilter, caCertificate);
    }
}
