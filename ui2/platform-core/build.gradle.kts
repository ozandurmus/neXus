// platform-core: opaque identifiers, result/error types, clocks and shared
// ports. Contract §2: no project dependencies (DIR-1).
dependencies {
    // Argon2id (contract §3.2, U-1): a crypto primitive, not a project
    // dependency -- DIR-1 forbids platform-core depending on another UI 2.0
    // module, not on an external library, exactly as GroupReferenceCipher
    // already uses javax.crypto for AES-GCM.
    implementation(libs.bouncycastle.provider)
    // Metrics (enterprise phase 0.4): Micrometer core + the Prometheus text registry; exposed as api so the service and worker
    // composition roots can register meters without a second declaration.
    api(libs.micrometer.registry.prometheus)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
