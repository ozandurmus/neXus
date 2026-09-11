// capability-registry: C4 registry, gate resolution and closed
// action/step types. Contract §2: platform-core only.
dependencies {
    api(project(":platform-core"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
