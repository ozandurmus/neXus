// service: Spring MVC API, session/RBAC interceptors, read models, static
// UI assets and composition root. Contract §2: platform-core, persistence,
// capability-registry, job-engine, ldap-adapter.
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    alias(libs.plugins.spring.boot)
    // Shared test helpers (AiviewReachableRoutes): the static masking audit here and the HTTP sweep in integration-tests
    // enumerate the aiview-reachable routes through one class.
    `java-test-fixtures`
}

dependencies {
    api(project(":platform-core"))
    api(project(":persistence"))
    api(project(":capability-registry"))
    api(project(":job-engine"))
    api(project(":ldap-adapter"))
    // Data Sanitizer engine (docs/design/DATA_SANITIZER_CONTRACT.md §4).
    implementation(project(":sanitizer"))
    implementation(project(":packet-analysis"))
    // Reports R1 (REPORTS_R1_CONTRACT.md "Renderers"): XLSX with the existing Apache POI, PDF with OpenPDF.
    implementation(libs.poi.ooxml)
    implementation(libs.openpdf)

    // Spring Boot 4 defaults Spring MVC's JSON conversion to Jackson 3 (tools.jackson). Every DTO,
    // annotation and JsonNode in this code base is Jackson 2, so the Jackson 3 starter is excluded and
    // Boot's Jackson 2 module supplies the ObjectMapper and the MappingJackson2HttpMessageConverter
    // (spring.http.converters.preferred-json-mapper=jackson2 in application.yml). With Jackson 3 absent
    // the standalone MockMvc tests also keep Spring Framework's Jackson 2 default converter.
    implementation(libs.spring.boot.starter.webmvc) {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-jackson")
    }
    implementation(libs.spring.boot.jackson2)
    // Spring Security 7 filter chain (SPRING_SECURITY_MIGRATION_PLAN_2026_11_15.md, Step 1: a passthrough chain,
    // no behaviour change; see NexusSecurityFilterChainConfiguration).
    implementation(libs.spring.boot.starter.security)
    // SAML 2.0 service provider for ADFS (migration plan Step 4): OpenSAML 5 response/assertion validation. Used as a
    // library by SamlAuthenticationService; no Saml2 servlet filter is registered, the ACS is our own controller.
    implementation(libs.spring.security.saml2.service.provider)
    // Security floor for the embedded server (see libs.versions.toml `tomcat`).
    constraints {
        implementation(libs.tomcat.embed.core)
        implementation(libs.tomcat.embed.el)
        implementation(libs.tomcat.embed.websocket)
        // One Bouncy Castle line (see libs.versions.toml): OpenSAML's transitive bcpkix/bcutil 1.77 would sit beside bcprov 1.86.
        implementation(libs.bouncycastle.provider)
        implementation(libs.bouncycastle.pkix)
        implementation(libs.bouncycastle.util)
        // Security floors for OpenSAML's transitive dependencies (dependency scan 2026-11-16: CVE-2026-102554 guava,
        // CVE-2026-54399 httpcore5, CVE-2026-54428 httpcore5-h2).
        implementation("com.google.guava:guava:33.7.2-jre") { because("CVE-2026-102554, fixed in 33.7.2") }
        implementation("org.apache.httpcomponents.core5:httpcore5:5.4.3") { because("CVE-2026-54399, fixed in 5.4.3") }
        implementation("org.apache.httpcomponents.core5:httpcore5-h2:5.4.3") { because("CVE-2026-54428, fixed in 5.4.3") }
        // Image scan 2026-11-17: CVE-2026-64607 (classic client leaks a pooled connection on an unsupported Content-Encoding), fixed in 5.6.3;
        // OpenSAML's metadata resolvers are the only consumer and run no request here. httpcore5 stays on the 5.4.3 line the client's own BOM names.
        implementation("org.apache.httpcomponents.client5:httpclient5:5.6.3") { because("CVE-2026-64607, fixed in 5.6.3") }
        implementation("org.apache.httpcomponents.client5:httpclient5-cache:5.6.3") { because("keeps the cache module on the client's line") }
        // velocity-engine-core 2.3 shades commons-io 2.8.0 (CVE-2024-47554, image scan 2026-11-16); 2.4.1 shades none.
        implementation("org.apache.velocity:velocity-engine-core:2.4.1") { because("embedded commons-io 2.8.0, CVE-2024-47554") }
    }
    // Failover Engine Stage 2 (durable schedules/ledger/quarantine): plain
    // JdbcTemplate over the same C1 section 6 DataSource bean, auto-configured
    // by Spring Boot once this starter is present -- no second DataSource, no
    // competing pool.
    implementation(libs.spring.boot.starter.jdbc)
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
    // Vendor support bundle upload (SFTP and SCP to a vendor case): the same maintained JSch fork the worker uses.
    implementation(libs.jsch)

    // PO ASSISTANT DECISION 2026-09-14 on packaging / DIR-2: the worker
    // role's classes reach the boot jar's runtime classpath only -- no
    // `service` source file imports a `worker` class (Ui2Launcher, in
    // platform-core, resolves the role's main class by name via
    // Class.forName against the boot jar's own class loader instead), so
    // this adds no class-level dependency edge for Ui2ArchitectureTest's
    // dir2 to catch, and dir2 stays unedited.
    runtimeOnly(project(":worker"))
    // cli_parity_for_every_ui_action: the CLI rides in the same boot jar (`cli` launcher role).
    runtimeOnly(project(":cli"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.security.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Upload tests run real SFTP and SCP exchanges against an in-process Apache MINA sshd. The jars are the ones Gradle itself
    // ships (the same sshd 2.12.1 the build tool uses), so no new module enters the dependency lock or verification metadata.
    gradle.gradleHomeDir?.resolve("lib/plugins")?.let { plugins ->
        testImplementation(fileTree(plugins) { include("sshd-core-*.jar", "sshd-common-*.jar", "sshd-sftp-*.jar") })
    }
}

// The test fixtures (AiviewReachableRoutes) compile against exactly the main code's own dependencies and version floors,
// so they add no module or version to the lock state.
configurations.named("testFixturesImplementation") {
    extendsFrom(configurations.implementation.get())
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
