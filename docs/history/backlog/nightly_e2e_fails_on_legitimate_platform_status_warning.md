# Nightly e2e fails when Platform status shows a legitimate disk or operations warning

status: planned · target: nightly e2e guard

2026-11-16 backlog refresh: found 2026-11-16. The nightly e2e fails when the Platform status page legitimately shows a disk or operations warning, so a real warning reads as a test failure. Open: decide which warnings the guard tolerates and keep failing on unexplained page or API errors.

2026-11-19: tightened e2e/safety.ts, e2e/fixtures.ts and e2e/every-tab.spec.ts so operational warnings with a stated reason and WARN/ATTENTION (degraded) platform health pass while error-severity alerts (including operational errors), down/unknown/reasonless health indicators, missing Platform status sections, failed requests and UNKNOWN shown as healthy fail; covered by tests/e2eSafety.test.ts and tests/e2ePlatformStatus.test.tsx.
