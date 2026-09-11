// integration-tests: Testcontainers PostgreSQL, Flyway lifecycle and
// cross-component contract tests; production code forbidden. Production
// modules are test-scope fixtures only.
//
// This slice (Slice A, no container runtime available in this environment)
// carries the source set and the Testcontainers dependency, but no test
// that requires a container runtime. See
// Ui2IntegrationHarnessPlaceholderTest for what Slice B must prove.
dependencies {
    testImplementation(project(":platform-core"))
    testImplementation(project(":persistence"))
    testImplementation(project(":capability-registry"))
    testImplementation(project(":job-engine"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
