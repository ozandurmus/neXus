# run_build.sh does not set the ui2-configuration image; it stays on :latest until set by hand

status: planned · target: deploy/ui2-image-build/run_build.sh

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): run_build.sh does not set the ui2-configuration image; it stays on :latest until set by hand

2026-11-14 backlog truth: DONE. Evidence: commits d121d3b3, c4e28001: run_build.sh NEXUS_DEPLOY_TARGETS includes configuration via module_deploy.py. Configuration image is set on every deploy
