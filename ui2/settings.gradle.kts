pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// UI 2.0 — B1-1 skeleton.
//
// Eleven Java subprojects, per contract Correction C-1: `frontend` is a
// build-time npm workspace driven by a root Exec task, NOT a Gradle
// subproject. `./ui2/gradlew -p ui2 projects` must list exactly these
// eleven entries.
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
)

// gradle/libs.versions.toml is auto-detected by Gradle's default version
// catalog convention as the "libs" catalog; no explicit
// dependencyResolutionManagement block is needed (and declaring one here
// too triggers "you can only call the 'from' method a single time").
