// ldap-adapter: UnboundID-backed operator-bind and re-validation adapter
// implementations. Contract §2: platform-core only; no web controller.
dependencies {
    api(project(":platform-core"))
    implementation(libs.unboundid.ldapsdk)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    // Behave like a Windows domain controller (Schannel refuses client-initiated renegotiation by default): the
    // in-process test directory then resets a connection that renegotiates, as a real DC does. Without this a
    // second startHandshake() on an established TLS 1.2 socket passes every test and fails in the field.
    systemProperty("jdk.tls.rejectClientInitiatedRenegotiation", "true")
    jvmArgs("-Djdk.tls.server.protocols=TLSv1.2")
}
