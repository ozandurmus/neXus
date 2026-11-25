// Test-runner image seed (ui2/Containerfile.test-runner; docs/design/HOSTA_TEST_NAMESPACE_2026_11_24.md, DRAFT).
// Used only while that image is built, where the build proxy is reachable: resolves the files of every resolvable
// configuration of every project once, so the network-less test pod finds every artifact in the read-only dependency
// cache (GRADLE_RO_DEP_CACHE) and runs Gradle with --offline. Lenient: an unresolvable optional configuration does not
// stop the seed; a run that misses an artifact answers ERROR (offline_miss) and the caller runs that tree on the Mac.
allprojects {
    tasks.register("seedTestDependencies") {
        doLast {
            project.configurations.filter { it.isCanBeResolved }.forEach { configuration ->
                configuration.incoming.artifactView { lenient(true) }.files.files
            }
        }
    }
}
