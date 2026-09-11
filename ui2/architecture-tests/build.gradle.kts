dependencies {
    testImplementation(platform(libs.junit.bom)); testImplementation(libs.junit.jupiter); testImplementation(libs.archunit)
    testImplementation(project(":platform-core")); testImplementation(project(":persistence")); testImplementation(project(":capability-registry")); testImplementation(project(":job-engine")); testImplementation(project(":ldap-adapter")); testImplementation(project(":service")); testImplementation(project(":worker")); testImplementation(project(":scheduler")); testImplementation(project(":cli"))
}
