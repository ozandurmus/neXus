// worker: job claimant/execution process and composition root; transport
// interfaces only at B1-1. Contract §2: platform-core, persistence,
// capability-registry, job-engine.
dependencies {
    api(project(":platform-core"))
    api(project(":persistence"))
    api(project(":capability-registry"))
    api(project(":job-engine"))
    // capability-registry is already reachable transitively via job-engine's
    // api(project(":capability-registry")), used directly here for
    // TransportKind/CapabilityRegistry (worker composition/startup wiring).

    // ssh_exec transport adapter (contract section 5, module-placement
    // table row "ssh_exec adapter... worker"). No test in this environment
    // exercises this against a real network endpoint or a real device
    // (contract section 1: no real device; container-hosted-endpoint tests
    // are disabled placeholders, see WorkerTransportContainerPlaceholderTest).
    implementation(libs.jsch)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
