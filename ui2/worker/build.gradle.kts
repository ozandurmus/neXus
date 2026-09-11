// worker: job claimant/execution process and composition root; transport
// interfaces only at B1-1. Contract §2: platform-core, persistence,
// capability-registry, job-engine.
dependencies {
    api(project(":platform-core"))
    api(project(":persistence"))
    api(project(":capability-registry"))
    api(project(":job-engine"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
