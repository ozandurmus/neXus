// architecture-tests: ArchUnit and artifact inspections; production code
// forbidden (src/main intentionally does not exist). Every Java module is
// a test-scope input only.
dependencies {
    testImplementation(project(":platform-core"))
    testImplementation(project(":persistence"))
    testImplementation(project(":capability-registry"))
    testImplementation(project(":job-engine"))
    testImplementation(project(":ldap-adapter"))
    testImplementation(project(":service"))
    testImplementation(project(":worker"))
    testImplementation(project(":scheduler"))
    testImplementation(project(":cli"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
