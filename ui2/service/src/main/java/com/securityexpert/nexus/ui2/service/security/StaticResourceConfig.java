package com.securityexpert.nexus.ui2.service.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.nio.file.Paths;

@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Expose the docs/design/ui2_mockups directory as static resources
        // The working directory is usually the repo root when running locally,
        // but in the container, it might be different. Let's map it safely.
        String currentPath = Paths.get("").toAbsolutePath().toString();
        // Since we don't know the exact container layout for docs, we might need to check if it exists.
        // But wait! Is docs/ copied into the Docker image?
    }
}
