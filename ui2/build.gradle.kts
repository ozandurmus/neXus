import org.gradle.api.plugins.JavaPluginExtension

plugins {
    id("org.cyclonedx.bom") version "3.4.1"
    id("org.springframework.boot") version "3.5.3" apply false
}

allprojects {
    group = "com.securityexpert.nexus"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(21) }
    }
    dependencyLocking { lockAllConfigurations() }
    dependencies { add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.12.2") }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

project(":service") { apply(plugin = "org.springframework.boot") }

tasks.register("unitTest") {
    dependsOn(subprojects.filter { it.name !in setOf("architecture-tests", "integration-tests") }.map { "${it.path}:test" })
}
tasks.register("architectureTest") { dependsOn(":architecture-tests:test") }
tasks.register("integrationTest") { dependsOn(":integration-tests:test") }
tasks.register("check") { dependsOn("unitTest", "architectureTest", "integrationTest", "cyclonedxBom", "frontendCheck") }
tasks.register<Exec>("frontendCheck") {
    workingDir("frontend")
    commandLine("npm", "ci")
    doLast {
        exec { workingDir("frontend"); commandLine("npm", "test", "--", "--run") }
        exec { workingDir("frontend"); commandLine("npm", "run", "build") }
    }
}
