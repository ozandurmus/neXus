# Release applies the committed manifest of service/configuration/compliance/policy; worker still image+fallback patch only

status: in_progress · target: tools/delivery/module_deploy.py (SYNCED_MANIFESTS)

2026-10-18: policy, service, configuration and compliance are now rendered with the candidate image, server-side diffed before the drain and applied after it (live replica count kept; rollback restores the digest only). Open: the worker (JAVA_TOOL_OPTIONS fallback flag is added at run time; rendering it into the manifest is needed first), and real-env validation on the next release.

2026-10-20: still open, restated as the follow-up 'worker manifest drift sync'. The release path syncs the policy, service, configuration and compliance manifests (1d059c56); the worker is still patched at run time (image plus the JAVA_TOOL_OPTIONS fallback flag), so its committed manifest can drift from the live object. Closure: render the fallback flag into the committed worker manifest, add the worker to SYNCED_MANIFESTS, validate on the next release. Real-env validation of the four synced modules is also pending.
