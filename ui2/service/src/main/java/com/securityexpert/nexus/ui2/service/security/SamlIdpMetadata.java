package com.securityexpert.nexus.ui2.service.security;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Collection;

import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.registration.AssertingPartyMetadata;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;

/**
 * Reads the identity provider data out of the metadata XML an administrator pastes (ADFS: {@code FederationMetadata.xml}).
 * Strictly from the pasted text: nothing is fetched, and the metadata's own signature is not evaluated (the administrator
 * vouches for what was pasted; the signing certificate that comes out of it is what every later assertion is verified with).
 */
final class SamlIdpMetadata {
    /** Longest metadata document accepted. */
    static final int MAX_BYTES = 1_048_576;

    record Parsed(String entityId, String ssoUrl, String binding, String signingCertsPem) { }

    private SamlIdpMetadata() { }

    /** @throws IllegalArgumentException with a plain-language message when the text is not usable IdP metadata */
    static Parsed parse(String xml) {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("The metadata is larger than 1 MB.");
        Collection<RelyingPartyRegistration.Builder> builders;
        try {
            builders = RelyingPartyRegistrations.collectionFromMetadata(new ByteArrayInputStream(bytes));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The text is not SAML identity provider metadata (an EntityDescriptor with an "
                    + "IDPSSODescriptor).");
        }
        if (builders.isEmpty()) {
            throw new IllegalArgumentException("The metadata holds no identity provider (IDPSSODescriptor).");
        }
        if (builders.size() > 1) {
            throw new IllegalArgumentException("The metadata describes more than one identity provider; paste the one for ADFS.");
        }
        AssertingPartyMetadata party;
        try {
            party = builders.iterator().next().registrationId(SamlSettingsService.REGISTRATION_ID)
                    .entityId("https://placeholder.invalid/sp")
                    .assertionConsumerServiceLocation("https://placeholder.invalid/acs").build().getAssertingPartyMetadata();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The metadata is incomplete (entity id or single sign-on service missing).");
        }
        StringBuilder pem = new StringBuilder();
        for (Saml2X509Credential credential : party.getVerificationX509Credentials()) {
            X509Certificate certificate = credential.getCertificate();
            pem.append(SamlPem.toPem(certificate));
        }
        if (pem.length() == 0) throw new IllegalArgumentException("The metadata holds no signing certificate.");
        Saml2MessageBinding binding = party.getSingleSignOnServiceBinding();
        return new Parsed(party.getEntityId(), party.getSingleSignOnServiceLocation(),
                binding == Saml2MessageBinding.POST ? "POST" : "REDIRECT", pem.toString());
    }
}
