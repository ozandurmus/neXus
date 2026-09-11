// scheduler: due-schedule evaluation and job-request creation; never
// device contact. Contract §2: platform-core, persistence, job-engine.
dependencies {
    api(project(":platform-core"))
    api(project(":persistence"))
    api(project(":job-engine"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
