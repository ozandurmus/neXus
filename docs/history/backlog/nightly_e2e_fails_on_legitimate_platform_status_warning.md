# Nightly e2e fails when Platform status shows a legitimate disk or operations warning

status: planned · target: nightly e2e guard

2026-11-16 backlog refresh: found 2026-11-16. The nightly e2e fails when the Platform status page legitimately shows a disk or operations warning, so a real warning reads as a test failure. Open: decide which warnings the guard tolerates and keep failing on unexplained page or API errors.
