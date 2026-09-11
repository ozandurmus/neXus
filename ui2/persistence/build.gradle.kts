// persistence: jOOQ access, transaction boundaries and Flyway integration
// code (contract §2 row; Amendment B1-1-A item 3 — the migration SQL
// resources themselves live under service/src/main/resources/db/migration,
// packaged into service, not here).
dependencies {
    api(project(":platform-core"))

    implementation(libs.jooq)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.postgresql)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
