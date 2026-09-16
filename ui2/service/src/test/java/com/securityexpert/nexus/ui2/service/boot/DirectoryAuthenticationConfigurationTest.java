package com.securityexpert.nexus.ui2.service.boot;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.securityexpert.nexus.ui2.identity.ldap.DirectoryProfile;
import com.securityexpert.nexus.ui2.identity.ldap.DirectoryTrustPolicy;
import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.Result;

class DirectoryAuthenticationConfigurationTest {
    @Test void initialOperatorAdmissionIsNotPostureGated() {
        var config = new DirectoryAuthenticationConfiguration();
        var mechanism = config.ldapMechanism(null, mock(DirectoryTrustPolicy.class));
        // Empty credentials are refused before any network access, not D6-unavailable.
        assertInstanceOf(AttemptOutcome.Refused.class, mechanism.attempt("synthetic", new char[0]));
    }

    @Test void revalidationRemainsDisabledWithoutLoadingSecrets() {
        var adapter = new DirectoryAuthenticationConfiguration().ldapRevalidationAdapter(null, null);
        assertInstanceOf(Result.Err.class, adapter.revalidatePrincipal("synthetic"));
    }

    @Test void absentProfileDoesNotComposeDirectoryAccess() {
        new ApplicationContextRunner().withUserConfiguration(DirectoryAuthenticationConfiguration.class)
            .run(context -> org.assertj.core.api.Assertions.assertThat(context).doesNotHaveBean(DirectoryProfile.class));
    }
}
