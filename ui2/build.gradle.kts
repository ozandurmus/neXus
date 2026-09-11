import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer

plugins {
    alias(libs.plugins.spring.boot) apply false
}

// UI 2.0 — B1-1 Gradle multi-module skeleton root build.
//
// Slice A scope only: no Docker/Containerfile work, no image build. The
// `frontend` npm workspace is driven from here as a root Exec-backed task
// per contract Correction C-1 — it is not a Gradle subproject.

val javaProjects = listOf(
    "platform-core",
    "persistence",
    "capability-registry",
    "job-engine",
    "ldap-adapter",
    "service",
    "worker",
    "scheduler",
    "cli",
    "architecture-tests",
    "integration-tests",
)

subprojects {
    if (name in javaProjects) {
        apply(plugin = "java-library")

        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
        }

        tasks.named<Test>("test") {
            useJUnitPlatform()
        }

        repositories {
            mavenCentral()
        }

        // Contract §3.1: Gradle dependency locking is required.
        dependencyLocking {
            lockAllConfigurations()
        }

        val testSourceSet = extensions.getByType<SourceSetContainer>()["test"]

        // architecture-tests and integration-tests each get their own
        // dedicated task name (contract §3.2 exact commands); every other
        // module gets `unitTest` in place of the default `test` task name.
        when (name) {
            "architecture-tests" -> tasks.register<Test>("architectureTest") {
                group = "verification"
                description = "Runs the Ui2ArchitectureTest ArchUnit suite (DIR-1..DIR-10)."
                testClassesDirs = testSourceSet.output.classesDirs
                classpath = testSourceSet.runtimeClasspath
                useJUnitPlatform()
            }
            "integration-tests" -> tasks.register<Test>("integrationTest") {
                group = "verification"
                description = "Runs Testcontainers-backed integration tests (none require a container in this slice)."
                testClassesDirs = testSourceSet.output.classesDirs
                classpath = testSourceSet.runtimeClasspath
                useJUnitPlatform()
            }
            else -> tasks.register<Test>("unitTest") {
                group = "verification"
                description = "Runs container-free, wall-clock-free unit tests."
                testClassesDirs = testSourceSet.output.classesDirs
                classpath = testSourceSet.runtimeClasspath
                useJUnitPlatform()
            }
        }
    }
}

// ---------------------------------------------------------------------
// Frontend: build-time npm workspace, not a Gradle subproject
// (Correction C-1). Root Exec tasks delegate to npm.
// ---------------------------------------------------------------------

val frontendDir = layout.projectDirectory.dir("frontend")

val frontendCi = tasks.register<Exec>("frontendCi") {
    group = "frontend"
    description = "npm ci in ui2/frontend"
    workingDir = frontendDir.asFile
    commandLine("npm", "ci")
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file("package-lock.json"))
    outputs.dir(frontendDir.dir("node_modules"))
}

val frontendTest = tasks.register<Exec>("frontendTest") {
    group = "frontend"
    description = "npm test in ui2/frontend"
    dependsOn(frontendCi)
    workingDir = frontendDir.asFile
    commandLine("npm", "test")
}

val frontendBuild = tasks.register<Exec>("frontendBuild") {
    group = "frontend"
    description = "npm run build in ui2/frontend"
    dependsOn(frontendCi)
    workingDir = frontendDir.asFile
    commandLine("npm", "run", "build")
}

val frontendCheck = tasks.register("frontendCheck") {
    group = "verification"
    description = "Full frontend gate: npm ci, npm test, npm run build."
    dependsOn(frontendCi, frontendTest, frontendBuild)
}

// ---------------------------------------------------------------------
// Root aggregation tasks: `./ui2/gradlew -p ui2 unitTest` and
// `architectureTest` run every subproject's own task of that name.
// ---------------------------------------------------------------------

tasks.register("unitTest") {
    group = "verification"
    description = "Aggregates unitTest across all UI 2.0 Java modules."
    dependsOn(subprojects.filter { it.name in javaProjects }.mapNotNull {
        it.tasks.findByName("unitTest")?.let { t -> "${it.path}:${t.name}" }
    })
}

tasks.register("architectureTest") {
    group = "verification"
    description = "Runs the ArchUnit direction-rule suite."
    dependsOn(":architecture-tests:architectureTest")
}

tasks.register("check") {
    group = "verification"
    description = "UI 2.0 aggregate gate: unitTest, architectureTest, frontendCheck."
    dependsOn("unitTest", "architectureTest", frontendCheck)
}
