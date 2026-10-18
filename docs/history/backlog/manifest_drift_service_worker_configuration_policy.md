# Release applies the committed manifest of service/configuration/compliance/policy; worker still image+fallback patch only

status: in_progress · target: tools/delivery/module_deploy.py (SYNCED_MANIFESTS)

2026-10-18: policy, service, configuration and compliance are now rendered with the candidate image, server-side diffed before the drain and applied after it (live replica count kept; rollback restores the digest only). Open: the worker (JAVA_TOOL_OPTIONS fallback flag is added at run time; rendering it into the manifest is needed first), and real-env validation on the next release.
