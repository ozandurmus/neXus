// capability-registry: C4 registry, gate resolution and closed
// action/step types. Contract §2: platform-core only.
dependencies {
    api(project(":platform-core"))

    // YAML capability-spec parsing (adjudication section 2 answer 5:
    // capability specs are YAML). No other capability-registry dependency
    // changes: this module still depends on platform-core only for
    // project dependencies (DIR-6).
    implementation(libs.snakeyaml)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
