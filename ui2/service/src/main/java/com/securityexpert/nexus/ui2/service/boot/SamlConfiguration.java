package com.securityexpert.nexus.ui2.service.boot;

import java.nio.file.Path;
import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.JooqSamlSettingsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SamlSettingsRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.service.security.SamlAuthenticationService;
import com.securityexpert.nexus.ui2.service.security.SamlSettingsService;

/**
 * SAML 2.0 service provider for ADFS (migration plan Step 4). Everything here is inert until an administrator saves the SAML
 * settings with {@code enabled = true}: no row means the three SAML routes answer 404 and nothing else changes. The optional SP
 * key pair can come from files ({@code UI2_SAML_SP_PRIVATE_KEY_PATH}, {@code UI2_SAML_SP_CERT_PATH}), which win over a key
 * stored encrypted in the settings (PO decision D5).
 */
@Configuration
public class SamlConfiguration {

    @Bean
    public SamlSettingsRepository samlSettingsRepository(TransactionBoundary transactionBoundary) {
        return new JooqSamlSettingsRepository(transactionBoundary);
    }

    @Bean
    public SamlSettingsService samlSettingsService(SamlSettingsRepository settings, RoleBindingRepository bindings,
            CredentialStoreCipher credentialStoreCipher, @Value("${ui2.credential-store.key-id}") String credentialStoreKeyId,
            GroupReferenceCipher groupReferenceCipher, ObjectProvider<SamlAuthenticationService> authentication,
            @Value("${ui2.saml.sp-private-key-path:}") String privateKeyPath,
            @Value("${ui2.saml.sp-cert-path:}") String certPath) {
        SamlSettingsService.SpKeyFiles files = privateKeyPath.isBlank() || certPath.isBlank() ? SamlSettingsService.SpKeyFiles.none()
                : new SamlSettingsService.SpKeyFiles(Path.of(privateKeyPath.strip()), Path.of(certPath.strip()));
        return new SamlSettingsService(settings, bindings, credentialStoreCipher, credentialStoreKeyId, groupReferenceCipher,
                Clock.systemUTC(), files, () -> authentication.getObject().reload());
    }

    @Bean
    public SamlAuthenticationService samlAuthenticationService(SamlSettingsService settings) {
        return new SamlAuthenticationService(settings, Clock.systemUTC(), LocalAuthenticationConfiguration.absoluteLifetime());
    }
}
