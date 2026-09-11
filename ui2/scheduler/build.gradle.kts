dependencies {
    implementation(project(":platform-core")); implementation(project(":persistence")); implementation(project(":job-engine"))
    testImplementation(platform(libs.junit.bom)); testImplementation(libs.junit.jupiter)
}
