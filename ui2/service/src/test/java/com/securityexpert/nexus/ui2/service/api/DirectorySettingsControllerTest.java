package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.identity.DirectoryProfileRepository;

public class DirectorySettingsControllerTest {

    @Test
    public void testGetLdapConfig() {
        DirectoryProfileRepository repository = mock(DirectoryProfileRepository.class);
        when(repository.findActiveProfile()).thenReturn(Optional.empty());

        DirectorySettingsController controller = new DirectorySettingsController(repository);
        assertNull(controller.getActiveProfile());
    }
}
