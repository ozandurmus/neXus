# hosta_export_all.sh does not export the ui2-build namespace (corp-ca ConfigMap) nor list the git-ignored ui2/.ca and Gradle zip

status: planned · target: scripts/hosta_export_all.sh

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): hosta_export_all.sh does not export the ui2-build namespace (corp-ca ConfigMap) nor list the git-ignored ui2/.ca and Gradle zip

2026-11-14 backlog truth: still open (P3, size S). Today: Rebuild export would miss build-namespace CA config. Evidence: scripts/hosta_export_all.sh only exports namespace ui2; no ui2-build export

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Commit 'hosta_export_all: export the ui2-build namespace (secrets 0600, configmaps) + stub-kubectl test' (tools/tests/test_hosta_export_all.py). Not run against the live host.
