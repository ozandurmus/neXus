// cli: thin typed command entry point using the same application ports.
// Contract §2: platform-core, job-engine.
dependencies {
    api(project(":platform-core"))
    api(project(":job-engine"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
