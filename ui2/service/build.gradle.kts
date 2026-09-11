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

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// This is a Java-only skeleton module at B1-1: no runnable Spring Boot
// fat jar is produced yet (no main class wired to it), so the bootJar
// task is disabled and the plain jar is kept.
tasks.named("bootJar") {
    enabled = false
}
tasks.named("jar") {
    enabled = true
}
