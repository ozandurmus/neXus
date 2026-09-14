// service: Spring MVC API, session/RBAC interceptors, read models, static
// UI assets and composition root. Contract §2: platform-core, persistence,
// capability-registry, job-engine, ldap-adapter.
import org.gradle.language.jvm.tasks.ProcessResources

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
    // LocalAuthenticationConfiguration builds its own DSLContext from that
    // DataSource (C3A contract §2/§5's composition root) -- :persistence
    // declares jOOQ as `implementation`, not `api`, so it is not otherwise
    // visible here.
    implementation(libs.jooq)

    // PO ASSISTANT DECISION 2026-09-14 on packaging / DIR-2: the worker
    // role's classes reach the boot jar's runtime classpath only -- no
    // `service` source file imports a `worker` class (Ui2Launcher, in
    // platform-core, resolves the role's main class by name via
    // Class.forName against the boot jar's own class loader instead), so
    // this adds no class-level dependency edge for Ui2ArchitectureTest's
    // dir2 to catch, and dir2 stays unedited.
    runtimeOnly(project(":worker"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// PO ASSISTANT DECISION 2026-09-14 on packaging: the launcher, not
// Ui2Application, is the boot jar's Start-Class -- args[0] selects the
// workload role (service/worker) before Spring ever starts.
springBoot {
    mainClass.set("com.securityexpert.nexus.ui2.platform.launch.Ui2Launcher")
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

// The shipped frontend comes from the frontend source at build time, not
// from a committed copy (one source of truth). `from(frontendBuild)` -- the
// task, not merely its output directory -- makes this a real Gradle
// input/output relationship: Gradle resolves the task's declared outputs,
// adds the task dependency automatically, and fingerprints the resulting
// files, so a frontend source change (which changes frontendBuild's
// declared output) is what makes this task, and the packaged artifact,
// out of date -- not just execution order.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.tasks.named("frontendBuild")) {
        into("static")
    }
}
