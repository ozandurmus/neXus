package com.securityexpert.nexus.ui2.service.boot;

import com.securityexpert.nexus.ui2.service.compliance.ComplianceWarmup;
import com.securityexpert.nexus.ui2.service.overview.ClusterDiffTask;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class, TickConfiguration.class);

    @Test void defaultAndExplicitTrueRegisterScheduledMethods() {
        for (String value : new String[]{"", "ui2.scheduling.enabled=true"}) {
            runner.withPropertyValues(value.isEmpty() ? new String[0] : new String[]{value}).run(context -> {
                assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
                assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
            });
        }
    }

    @Test void disabledDoesNotRegisterAnyScheduledMethodsOrStartupWarmers() {
        runner.withUserConfiguration(ComplianceWarmup.class, ClusterDiffTask.class)
                .withPropertyValues("ui2.scheduling.enabled=false").run(context -> {
                    assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                    assertThat(context).doesNotHaveBean(ComplianceWarmup.class).doesNotHaveBean(ClusterDiffTask.class);
                    assertThat(context).hasSingleBean(Tick.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TickConfiguration {
        @Bean Tick tick() { return new Tick(); }
    }

    static class Tick {
        @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
        public void run() { throw new AssertionError("Test must not execute a scheduled task"); }
    }
}
