package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.persistence.identity.DirectoryProfileRecord;
import com.securityexpert.nexus.ui2.persistence.identity.DirectoryProfileRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/v2/config/ldap")
public class DirectorySettingsController {
    private final DirectoryProfileRepository repository;

    public DirectorySettingsController(DirectoryProfileRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public DirectoryProfileRecord getActiveProfile() {
        return repository.findActiveProfile().orElse(null);
    }

    @PutMapping
    public DirectoryProfileRecord saveProfile(@RequestBody DirectoryProfileRecord record) {
        return repository.save(record);
    }
}
