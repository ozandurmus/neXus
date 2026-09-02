---
applyTo: "main.py,config.py,utils/runtime_*.py,utils/run_context.py,utils/snapshot.py,utils/support_bundle.py,utils/logger.py"
---

# Runtime Boundary Contract

Repository/application root and operational RuntimeRoot are separate;
normal runtime data/output/log state belongs outside the repository. Do not
reintroduce repository-relative runtime writes or silent dual-root
fallbacks. Templates/static source assets remain repository-owned.

`RuntimeAuth` is the authoritative in-memory authentication boundary. Do
not persist or serialize authentication secrets. Keep protected
representations and redaction semantics intact.

History/CAS runtime-boundary migration is deferred to the explicit
pre-server storage-sensitive checkpoint; do not pull it into unrelated
builds.
