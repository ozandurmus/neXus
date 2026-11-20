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
    testImplementation(project(":sanitizer"))
    testImplementation(project(":packet-analysis"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// ArchUnit imports every UI 2.0 module's compiled classes once per test JVM
// (Ui2ArchitectureTest.productionClasses); keep a single non-recycled fork with
// an explicit 2 GB heap on both `test` and `architectureTest` so local and CI runs
// share the same cached class graph and memory ceiling.
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    maxParallelForks = 1
    forkEvery = 0
}
