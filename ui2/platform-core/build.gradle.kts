// platform-core: opaque identifiers, result/error types, clocks and shared
// ports. Contract §2: no project dependencies (DIR-1).
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
