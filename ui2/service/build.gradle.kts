// service: Spring MVC API, session/RBAC interceptors, read models, static
// UI assets and composition root. Contract §2: platform-core, persistence,
// capability-registry, job-engine, ldap-adapter.
plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    api(project(":platform-core"))
    api(project(":persistence"))
    api(project(":capability-registry"))
    api(project(":job-engine"))
    api(project(":ldap-adapter"))

    implementation(libs.spring.boot.starter.web)
    // The composition root builds its own DataSource under C1 section 6's
    // secret-file rule, so the driver is a compile dependency here rather
    // than only a transitive runtime one of :persistence.
    implementation(libs.postgresql)
    implementation(libs.flyway.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Phase 1 step 1 wired the composition root
// (service/boot/Ui2Application.java), so the module is now a runnable Spring
// Boot application and bootJar is enabled. The plain jar stays enabled too:
// integration-tests consumes this module as a library.
tasks.named("bootJar") {
    enabled = true
}
tasks.named("jar") {
    enabled = true
}
