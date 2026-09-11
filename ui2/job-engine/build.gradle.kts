// job-engine: C2 job state machine, worker/scheduler ports and admission
// interfaces. Contract §2: platform-core, persistence, capability-registry.
dependencies {
    api(project(":platform-core"))
    api(project(":persistence"))
    api(project(":capability-registry"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
