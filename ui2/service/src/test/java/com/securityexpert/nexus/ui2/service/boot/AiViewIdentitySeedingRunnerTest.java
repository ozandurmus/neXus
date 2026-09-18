package com.securityexpert.nexus.ui2.service.boot;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.securityexpert.nexus.ui2.persistence.identity.FirstBootIdentityRoleBindingSeeder;
import com.securityexpert.nexus.ui2.persistence.identity.FirstBootIdentityRoleBindingSeeder.IdentitySpec;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.platform.RoleToken;

import static org.assertj.core.api.Assertions.assertThat;

class AiViewIdentitySeedingRunnerTest {

    @Test
    void seedsAiViewWhenAbsent() {
        LocalCredentialsRepository repository = mock(LocalCredentialsRepository.class);
        FirstBootIdentityRoleBindingSeeder seeder = mock(FirstBootIdentityRoleBindingSeeder.class);

        when(repository.findByName(BootstrapCredentialDefaults.AIVIEW_NAME)).thenReturn(Optional.empty());

        AiViewIdentitySeedingRunner runner = new AiViewIdentitySeedingRunner(repository, seeder);
        runner.run(null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IdentitySpec>> captor = ArgumentCaptor.forClass(List.class);
        verify(seeder).seed(captor.capture());

        List<IdentitySpec> specs = captor.getValue();
        assertThat(specs).hasSize(1);
        IdentitySpec spec = specs.get(0);
        assertThat(spec.localIdentityName()).isEqualTo("aiview");
        assertThat(spec.roleTokens()).contains(RoleToken.REPLAY_VIEWER, RoleToken.VIEWER);
    }

    @Test
    void skipsSeedingWhenAiViewAlreadyExists() {
        LocalCredentialsRepository repository = mock(LocalCredentialsRepository.class);
        FirstBootIdentityRoleBindingSeeder seeder = mock(FirstBootIdentityRoleBindingSeeder.class);
        LocalCredentialRecord existing = mock(LocalCredentialRecord.class);

        when(repository.findByName(BootstrapCredentialDefaults.AIVIEW_NAME)).thenReturn(Optional.of(existing));

        AiViewIdentitySeedingRunner runner = new AiViewIdentitySeedingRunner(repository, seeder);
        runner.run(null);

        verify(seeder, never()).seed(anyList());
    }
}
