package com.securityexpert.nexus.ui2.service.snmp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.snmp.JooqSnmpPollingRepository;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPollingRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort;

/** SNMP device metrics on the service side: the V268 store, and the Administration > SNMP polling settings. */
@Configuration(proxyBeanMethods = false)
public class SnmpPollingConfiguration {

    @Bean
    public SnmpPollingRepository snmpPollingRepository(TransactionBoundary transactions) {
        return new JooqSnmpPollingRepository(transactions);
    }

    @Bean
    public SnmpPollingSettingsService snmpPollingSettingsService(SnmpPollingRepository repository,
            CredentialStorePort credentialStore) {
        return new SnmpPollingSettingsService(repository, credentialStore::list);
    }
}
