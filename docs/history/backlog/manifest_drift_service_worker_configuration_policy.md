# Release applies the committed manifest of service/configuration/compliance/policy; worker still image+fallback patch only

status: in_progress · target: tools/delivery/module_deploy.py (SYNCED_MANIFESTS)

2026-10-18: policy, service, configuration and compliance are now rendered with the candidate image, server-side diffed before the drain and applied after it (live replica count kept; rollback restores the digest only). Open: the worker (JAVA_TOOL_OPTIONS fallback flag is added at run time; rendering it into the manifest is needed first), and real-env validation on the next release.

2026-10-20: still open, restated as the follow-up 'worker manifest drift sync'. The release path syncs the policy, service, configuration and compliance manifests (1d059c56); the worker is still patched at run time (image plus the JAVA_TOOL_OPTIONS fallback flag), so its committed manifest can drift from the live object. Closure: render the fallback flag into the committed worker manifest, add the worker to SYNCED_MANIFESTS, validate on the next release. Real-env validation of the four synced modules is also pending.

2026-11-14 backlog truth: still open (P2, size S). Today: Worker manifest can drift from the live object. Evidence: module_deploy.py SYNCED_MANIFESTS has service, configuration, compliance, policy; worker absent (patched at run time)

2026-11-14: worker manifest sync in ship batch 3.

2026-11-15 10:00 backlog refresh: 2026-11-15: worker is now in SYNCED_MANIFESTS (tools/delivery/module_deploy.py; commit 'Fail fast on failed module e2e jobs and sync worker manifest on deploy', on main). Left open: real-env validation of the synced modules on a release.

2026-11-16 backlog refresh (origin/main db9b2ecd, live through batch 14, schema V242): the backup module manifest joined the worker in SYNCED_MANIFESTS (service, configuration, compliance, policy, backup, worker). The deploys of batches 10-14 applied them; no dedicated drift check on the live objects was run in this refresh, so real-env validation stays open.
