// ldap-adapter: UnboundID-backed operator-bind and re-validation adapter
// implementations. Contract §2: platform-core only; no web controller.
dependencies {
    api(project(":platform-core"))
    implementation(libs.unboundid.ldapsdk)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
