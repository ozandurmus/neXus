dependencies {
    testImplementation(platform(libs.junit.bom)); testImplementation(libs.junit.jupiter); testImplementation(libs.flyway); testImplementation(libs.postgresql); testImplementation(libs.testcontainers.junit); testImplementation(libs.testcontainers.postgresql)
    testImplementation(project(":persistence")); testImplementation(project(":service"))
}

tasks.test { systemProperty("junit.jupiter.execution.parallel.enabled", "false") }
