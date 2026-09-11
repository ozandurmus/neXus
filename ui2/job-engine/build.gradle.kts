dependencies {
    api(project(":platform-core")); implementation(project(":persistence")); implementation(project(":capability-registry"))
    testImplementation(platform(libs.junit.bom)); testImplementation(libs.junit.jupiter)
}
