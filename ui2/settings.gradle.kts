pluginManagement {
    repositories {
        // Maven Central first: the Spring Boot plugin marker POM Central publishes is the one whose sha256 Central
        // also publishes (the plugin portal serves its own generated marker with a different checksum), so every
        // entry in gradle/verification-metadata.xml can be cross-checked against Maven Central.
        mavenCentral()
        gradlePluginPortal()
    }
}

// UI 2.0 — B1-1 skeleton.
//
// Twelve Java subprojects, per contract Correction C-1: `frontend` is a
// build-time npm workspace driven by a root Exec task, NOT a Gradle
// subproject. `./ui2/gradlew -p ui2 projects` must list exactly these
// twelve entries.
rootProject.name = "ui2"

include(
    "platform-core",
    "persistence",
    "capability-registry",
    "job-engine",
    "ldap-adapter",
    "service",
    "worker",
    "scheduler",
    "cli",
    "architecture-tests",
    "integration-tests",
    // Data Sanitizer engine (docs/design/DATA_SANITIZER_CONTRACT.md §4).
    "sanitizer",
    "packet-analysis",
)

// gradle/libs.versions.toml is auto-detected by Gradle's default version
// catalog convention as the "libs" catalog; no explicit
// dependencyResolutionManagement block is needed (and declaring one here
// too triggers "you can only call the 'from' method a single time").
