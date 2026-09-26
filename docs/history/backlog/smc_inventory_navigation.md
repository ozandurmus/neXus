# SMC managed-device navigation and configuration refresh visibility

status: in_progress · target: ui2/frontend/src/screens/InventoryPanels.tsx

Measured stored SMC evidence: one enrolled manager, ten completed inventory jobs, seven members in latest inventory, zero discovery runs/candidates. AIView confirmed children exist on Managed devices while default Interfaces incorrectly looked empty; listed-child clicks selected only the manager. Fix defaults and child selection/address visibility, dynamic vendor filters, and configuration projection/text refresh after collection. No device contacted. Fortinet configuration evidence remains missing on nine enrolled gateways after historical skipped onboarding; normal collection is required, not fabricated data.

Correction to coverage wording: one of ten enrolled Fortinet devices has configuration evidence; this does not establish that nine gateways lack it. UI tests 207/207 and production build passed. Changes are display/navigation/refresh fixes, not new collectors or device commands.

PO approved merge/deploy. Code c27014a deployed through scripts/hosta_deploy.sh; site 200, schema 91, service/worker/configuration ready 1/1 and configuration digest matched. Live AIView inspection before deploy confirmed all seven SMC members; post-deploy reload returned to sign-in, so new UI visual acceptance awaits login. No device command sent. Task-start account-wide weekly use was 36 percent; observed completion checkpoint is 50 percent, not attributable solely to this task.
