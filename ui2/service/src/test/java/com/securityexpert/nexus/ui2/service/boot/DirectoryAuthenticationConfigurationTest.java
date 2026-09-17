package com.securityexpert.nexus.ui2.service.boot;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.securityexpert.nexus.ui2.identity.ldap.DirectoryProfile;
import com.securityexpert.nexus.ui2.identity.ldap.DirectoryTrustPolicy;
import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.Result;
import com.securityexpert.nexus.ui2.persistence.identity.DirectoryProfileRepository;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import java.util.Optional;

class DirectoryAuthenticationConfigurationTest {
    @Test void initialOperatorAdmissionIsNotPostureGated() {
        var config = new DirectoryAuthenticationConfiguration();
        var repo = mock(DirectoryProfileRepository.class);
        when(repo.findActiveProfile()).thenReturn(Optional.empty());
        var provider = config.dynamicDirectoryProvider(repo, mock(ActorAuthzStateRepository.class));
        var mechanism = config.ldapMechanism(provider);
        assertInstanceOf(AttemptOutcome.MechanismUnavailable.class, mechanism.attempt("synthetic", new char[0]));
    }

    @Test void revalidationRemainsDisabledWithoutLoadingSecrets() {
        var config = new DirectoryAuthenticationConfiguration();
        var repo = mock(DirectoryProfileRepository.class);
        when(repo.findActiveProfile()).thenReturn(Optional.empty());
        var provider = config.dynamicDirectoryProvider(repo, mock(ActorAuthzStateRepository.class));
        var adapter = config.ldapRevalidationAdapter(provider);
        assertInstanceOf(Result.Err.class, adapter.revalidatePrincipal("synthetic"));
    }

    @Test void absentProfileDoesNotComposeDirectoryAccess() {
        new ApplicationContextRunner().withUserConfiguration(DirectoryAuthenticationConfiguration.class)
            .withBean(ActorAuthzStateRepository.class, () -> mock(ActorAuthzStateRepository.class))
            .withBean(com.securityexpert.nexus.ui2.persistence.TransactionBoundary.class, () -> mock(com.securityexpert.nexus.ui2.persistence.TransactionBoundary.class))
            .run(context -> org.assertj.core.api.Assertions.assertThat(context).doesNotHaveBean(DirectoryProfile.class));
    }
}
