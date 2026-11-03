// packet-analysis: the Java port of the frozen PacketBreaker engine
// (docs/design/PACKET_ANALYSIS_PORT_CONTRACT.md). Plain Java, no Spring,
// no project dependencies.
dependencies {
    // Per-case project store (same engine version as the reference).
    implementation(libs.duckdb.jdbc)
    // Streaming JSON parser for stored settings and capture inventories (the writer is PyJson).
    implementation(libs.jackson.databind)
    // Expert report workbook (native Excel charts): POI's XSSF/XDDF writer, repacked deterministically.
    implementation(libs.poi.ooxml)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Parity runs: `./gradlew -q :packet-analysis:printRuntimeClasspath` then
// `java -cp <classpath> com.securityexpert.nexus.ui2.packetanalysis.ingest.IngestCli ...`.
tasks.register("printRuntimeClasspath") {
    val runtime = project.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    dependsOn("classes")
    doLast { println(runtime.asPath) }
}

// The frozen reference fixtures were exported on a machine in Turkey: their "local" time labels read +03:00
// (Headlines uses the JVM default zone when the topology names none). Pin the zone of the test JVM so the
// parity tests do not depend on the host: a UTC CI runner otherwise renders +00:00 and every such section differs.
tasks.withType<Test>().configureEach {
    systemProperty("user.timezone", "Europe/Istanbul")
}
