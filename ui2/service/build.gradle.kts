dependencies {
    implementation(libs.spring.boot); implementation(libs.flyway); implementation(libs.flyway.postgresql); runtimeOnly(libs.postgresql); implementation(project(":platform-core")); implementation(project(":persistence")); implementation(project(":capability-registry")); implementation(project(":job-engine")); implementation(project(":ldap-adapter"))
    testImplementation(platform(libs.junit.bom)); testImplementation(libs.junit.jupiter)
}
