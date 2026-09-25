package com.securityexpert.nexus.ui2.service.device.onboarding;

import java.util.Objects;

import org.springframework.scheduling.annotation.Scheduled;

/** Advances every RUNNING onboarding flow whose current job is terminal (every 5 s; reads only, admits reads). */
public class OnboardingFlowScheduler {

    private final OnboardingFlowService flows;

    public OnboardingFlowScheduler(OnboardingFlowService flows) {
        this.flows = Objects.requireNonNull(flows, "flows");
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 20_000)
    public void tick() {
        flows.advanceAll();
    }
}
