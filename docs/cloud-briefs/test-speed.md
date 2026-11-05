Repo github.com/ozandurmus/neXus, branch: git fetch origin cloud-base/1105 && git checkout -b cloud/test-speed-1105 origin/cloud-base/1105. Read AGENTS.md first. No access to the live system.

TASK: speed up the Java tests without losing coverage (`cd ui2 && ./gradlew :service:test :persistence:test :worker:test :architecture-tests:test`, 10+ minutes, up to 58 under contention).
- Profile the suite, and list the slowest 30 classes with the reason each is slow.
- Inject Clock instead of sleeps and wall-clock reads.
- Shorten retry backoffs in tests.
- Use Gradle and JUnit parallelism where tests are isolated.
- Make FailoverScheduleServiceTest deterministic (it failed once with BASELINE_EVIDENCE_UNAVAILABLE).
- Never weaken assertions.
Record before and after timings in docs/design/TEST_SPEED_2026_11_05.md.
Commit (messages end with 'Co-Authored-By: Claude'), git push origin cloud/test-speed-1105, no PR.