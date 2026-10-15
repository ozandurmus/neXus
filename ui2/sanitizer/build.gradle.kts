// sanitizer: the Data Sanitizer engine (docs/design/DATA_SANITIZER_CONTRACT.md §4) -- detectors, keyed mappers and
// streaming format handlers for text/log, CSV, XLSX, JSON and pcap/pcapng. Plain Java: no Spring, no JDBC, no
// network, and no dependency on another UI 2.0 module; the service passes the key, the device-name pseudonyms and
// the mapping vault in through small interfaces (SanitizerModuleFenceTest).
dependencies {
    // XLSX parts are read through POI's OOXML package model (zip-bomb limits, content types) and rewritten as
    // streams; the test suite also uses POI's SXSSF writer to build its million-row workbook.
    implementation(libs.poi.ooxml)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
