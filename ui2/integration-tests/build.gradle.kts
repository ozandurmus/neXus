// integration-tests: real-PostgreSQL-16 Flyway lifecycle and cross-component
// contract tests; production code forbidden. Production modules are
// test-scope fixtures only.
//
// UI2_0_B1_02_SCHEMA_V1_CONTRACT.md §7 names Testcontainers as the carrier;
// the load-bearing requirement is a real PostgreSQL 16 server. Ui2PostgresFixture
// therefore accepts either an already-running server via UI2_TEST_JDBC_URL or a
// Testcontainers postgres:16 container, and fails closed when neither exists --
// hence both the Testcontainers dependency and a direct JDBC/Flyway one.
//
// Flyway, the PostgreSQL driver and jOOQ are declared testImplementation (not
// merely inherited at runtime through :persistence's `implementation` scope)
// because the harness and its tests name MigrateResult, PGSimpleDataSource and
// DSLContext in their own source.
dependencies {
    testImplementation(project(":platform-core"))
    testImplementation(project(":persistence"))
    testImplementation(project(":capability-registry"))
    testImplementation(project(":job-engine"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)

    testImplementation(libs.flyway.core)
    testImplementation(libs.postgresql)
    testImplementation(libs.jooq)
    testRuntimeOnly(libs.flyway.database.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}
