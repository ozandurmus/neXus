package com.securityexpert.nexus.ui2.service.boot;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.service.projectplan.ProjectPlanReader;

/**
 * Wires the {@link ProjectPlanReader} bean. Unlike {@link DatabaseConfiguration}'s
 * secret files, a missing project-plan source is not a startup failure --
 * WORKER.md AC-1 requires the read endpoint itself to degrade gracefully,
 * file by file, rather than the application refusing to boot. The directory
 * is therefore configurable (an operator can point it at a checked-out
 * repository explicitly) but never required: an unset property falls back to
 * {@link ProjectPlanReader#locateRepositoryProjectDirectory()}.
 */
@Configuration
public class ProjectPlanConfiguration {

    @Bean
    public ProjectPlanReader projectPlanReader(
            @Value("${ui2.project-plan.directory:}") String configuredDirectory) {
        Path directory = configuredDirectory == null || configuredDirectory.isBlank()
                ? ProjectPlanReader.locateRepositoryProjectDirectory()
                : Path.of(configuredDirectory);
        return new ProjectPlanReader(directory);
    }
}
